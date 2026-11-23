//! Windows: double-clicking `dishus.exe` (no command) installs DiShus for the current user and
//! pairs a phone. No administrator rights are used.
//!
//! * copies itself to `%LOCALAPPDATA%\Programs\dishus\dishus.exe` and writes the daemon next to
//!   it (the release exe carries the daemon inside; see `build.rs`),
//! * adds that folder to the user PATH and starts the daemon at sign-in (HKCU Run key),
//! * (re)starts the daemon, then shows the pairing QR code, a new one on Enter when it expires.
//!
//! `dishus uninstall` undoes all of it and keeps the pairings and keys in `%APPDATA%\dishus`.

use std::{
    io::{BufRead, Write},
    os::windows::process::CommandExt,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    time::{Duration, Instant},
};

use anyhow::{Context, Result, bail};
use dishus_win::reg;

use dishus_proto::ipc::{IpcRequest, IpcResponse};

use crate::{call, pair_once};

/// The daemon, built into release exes by `build.rs` (`DISHUS_EMBED_DAEMON`).
#[cfg(dishus_embed_daemon)]
const DAEMON: Option<&[u8]> = Some(include_bytes!(concat!(
    env!("OUT_DIR"),
    "/dishus-daemon.exe"
)));
#[cfg(not(dishus_embed_daemon))]
const DAEMON: Option<&[u8]> = None;

const RUN_VALUE: &str = "DiShus";
const DAEMON_EXE: &str = "dishus-daemon.exe";
const GPU_PREFS: &str = r"Software\Microsoft\DirectX\UserGpuPreferences";
const TOAST_KEY: &str = r"Software\Classes\AppUserModelId\DiShus.Daemon";

const DETACHED_PROCESS: u32 = 0x0000_0008;
const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

fn install_dir() -> Result<PathBuf> {
    let (_, local) = dishus_win::user_dirs()?;
    Ok(local.join("Programs").join("dishus"))
}

/// False if there is no one to press Enter (stdin closed).
fn wait_enter(prompt: &str) -> bool {
    print!("{prompt}");
    let _ = std::io::stdout().flush();
    let mut s = String::new();
    matches!(std::io::stdin().lock().read_line(&mut s), Ok(n) if n > 0)
}

/// End this user's running daemon (and its capture worker), quietly.
fn stop_daemons() {
    if let Err(e) = dishus_win::kill_own(DAEMON_EXE) {
        println!("  ! could not stop the running DiShus: {e}");
    }
}

/// Write `data` to `path`, retrying while a just-ended process still holds the file.
fn write_retry(path: &Path, data: &[u8]) -> Result<()> {
    let until = Instant::now() + Duration::from_secs(10);
    loop {
        match std::fs::write(path, data) {
            Ok(()) => return Ok(()),
            Err(e) if Instant::now() < until => {
                let _ = e;
                std::thread::sleep(Duration::from_millis(200));
            }
            Err(e) => return Err(e).with_context(|| format!("writing {}", path.display())),
        }
    }
}

fn install() -> Result<PathBuf> {
    let dir = install_dir()?;
    std::fs::create_dir_all(&dir).with_context(|| format!("creating {}", dir.display()))?;
    let me = std::env::current_exe().context("locating own executable")?;
    let demon = dir.join(DAEMON_EXE);
    let demon_bytes = match DAEMON {
        Some(b) => b.to_vec(),
        // Development builds: the daemon next to this exe.
        None => {
            let sibling = me.with_file_name(DAEMON_EXE);
            std::fs::read(&sibling).with_context(|| {
                format!(
                    "this build carries no daemon and {} is missing",
                    sibling.display()
                )
            })?
        }
    };

    println!("Installing for this user in {}", dir.display());
    stop_daemons();
    write_retry(&demon, &demon_bytes)?;
    let cli = dir.join("dishus.exe");
    let running_installed = std::fs::canonicalize(&me).ok() == std::fs::canonicalize(&cli).ok();
    if !running_installed {
        write_retry(&cli, &std::fs::read(&me).context("reading own executable")?)?;
    }

    reg::set(
        reg::RUN_KEY,
        RUN_VALUE,
        &format!("\"{}\"", demon.display()),
        false,
    )
    .context("setting DiShus to start at sign-in")?;
    let dir_s = dir.to_string_lossy().into_owned();
    if reg::add_to_user_path(&dir_s).context("adding DiShus to PATH")? {
        println!("  ✓ added to PATH (new terminals can run `dishus`)");
    }
    println!("  ✓ starts by itself when you sign in");

    Command::new(&demon)
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .creation_flags(DETACHED_PROCESS | CREATE_NEW_PROCESS_GROUP)
        .spawn()
        .with_context(|| format!("starting {}", demon.display()))?;
    Ok(dir)
}

/// Wait for the daemon to answer and report what it found.
async fn wait_ready() -> Result<()> {
    let until = Instant::now() + Duration::from_secs(20);
    loop {
        match call(IpcRequest::Status, Duration::from_secs(3)).await {
            Ok(IpcResponse::Status(s)) => {
                println!("  ✓ DiShus is running on \"{}\"", s.pc_name);
                if s.lockdown {
                    bail!("DiShus is locked (`dishus kill` was used). Run `dishus unlock` first.");
                }
                return Ok(());
            }
            _ if Instant::now() < until => tokio::time::sleep(Duration::from_millis(300)).await,
            Ok(r) => bail!("unexpected reply {r:?}"),
            Err(e) => return Err(e.context("DiShus did not start")),
        }
    }
}

async fn setup() -> Result<()> {
    println!("DiShus {} for Windows\n", dishus_proto::VERSION);
    install()?;
    wait_ready().await?;
    println!(
        "\nIf Windows asks whether DiShus may use the network, click Allow.\n\
         On the phone, open the DiShus app and tap Pair new PC.\n"
    );
    let mut first = true;
    loop {
        if pair_once(first).await? {
            break;
        }
        first = false;
        if !wait_enter("\nThe QR code expired. Press Enter for a new one, or close this window.") {
            bail!("pairing window expired");
        }
        println!();
    }
    offer_ssh();
    println!(
        "\nDone. DiShus runs in the background and starts by itself when you sign in.\n\
         On the phone, tap this PC to connect.\n\
         To pair another phone later, open this file again or run `dishus pair`."
    );
    Ok(())
}

/// Runs elevated: installs and starts Windows' own OpenSSH server and opens port 22.
const SSH_SCRIPT: &str = r#"$ErrorActionPreference = 'Stop'
try {
    if (-not (Get-Service sshd -ErrorAction SilentlyContinue)) {
        Write-Host 'Windows is downloading OpenSSH Server from Windows Update.'
        Write-Host 'This is Windows itself, not DiShus: the first time it often takes 5-15 minutes.'
        Write-Host 'Please do not close this window.'
        Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0 | Out-Null
    }
    Set-Service sshd -StartupType Automatic
    Start-Service sshd
    if (-not (Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH Server (sshd)' `
            -Enabled True -Direction Inbound -Protocol TCP -Action Allow -LocalPort 22 | Out-Null
    }
} catch {
    Write-Host "Error: $_"
    Read-Host 'Press Enter to close'
    exit 1
}
"#;

/// Optional: turn on SSH for the phone's SSH mode. It needs administrator rights (one UAC
/// prompt), so it is only done when the user answers yes.
fn offer_ssh() {
    print!(
        "\nAlso enable SSH, so the phone's SSH mode can open a terminal on this PC?\n\
         Windows will ask for administrator rights once. [y/N] "
    );
    let _ = std::io::stdout().flush();
    let mut answer = String::new();
    if std::io::stdin().lock().read_line(&mut answer).is_err()
        || !answer.trim().eq_ignore_ascii_case("y")
    {
        return;
    }
    println!(
        "  Setting up SSH in a separate window. If Windows has to download OpenSSH first, that\n  \
         download (Windows Update, not DiShus) can take 5-15 minutes. Please wait."
    );
    if let Err(e) = enable_ssh() {
        println!("  ! SSH was not enabled: {e:#}");
        return;
    }
    println!("  ✓ SSH is on (port 22, starts with Windows)");
    let ip = local_ip().map_or_else(|| "(see ipconfig)".to_owned(), |ip| ip.to_string());
    let user = std::env::var("USERNAME").unwrap_or_default();
    println!(
        "  On the phone: SSH, pick this PC, username {user}, and your Windows password (not the PIN).\n  \
         From another computer: ssh {user}@{ip}"
    );
}

fn enable_ssh() -> Result<()> {
    let script = std::env::temp_dir().join("dishus-enable-ssh.ps1");
    std::fs::write(&script, SSH_SCRIPT).with_context(|| format!("writing {}", script.display()))?;
    // The path goes through an env var so no quoting of it is needed on this command line;
    // [char]34 is the double quote the elevated command line needs around it.
    let status = Command::new("powershell")
        .env("DISHUS_SSH_PS1", &script)
        .args(["-NoProfile", "-Command"])
        .arg(
            "$p = Start-Process powershell -Verb RunAs -Wait -PassThru -ArgumentList \
             ('-NoProfile -ExecutionPolicy Bypass -File ' + [char]34 + $env:DISHUS_SSH_PS1 + [char]34); \
             if (-not $p) { exit 1 }; exit $p.ExitCode",
        )
        .status()
        .context("starting PowerShell");
    let _ = std::fs::remove_file(&script);
    if !status?.success() {
        bail!("cancelled, or the setup failed");
    }
    Ok(())
}

/// The LAN address other devices reach this PC on. Connecting a UDP socket sends nothing; it
/// only picks the outgoing interface.
// shortcut: with a VPN up on the PC this may print the VPN address; ipconfig then has the right one.
fn local_ip() -> Option<std::net::IpAddr> {
    let s = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    s.connect("8.8.8.8:80").ok()?;
    s.local_addr().ok().map(|a| a.ip())
}

pub async fn run() -> Result<()> {
    if let Err(e) = setup().await {
        println!("\nError: {e:#}");
        wait_enter("\nPress Enter to close this window.");
        std::process::exit(1);
    }
    wait_enter("\nPress Enter to close this window.");
    Ok(())
}

/// `dishus uninstall`: stop DiShus, remove it from sign-in and PATH, delete the program files.
pub fn uninstall() -> Result<()> {
    let dir = install_dir()?;
    let demon = dir.join(DAEMON_EXE);
    stop_daemons();
    reg::delete(reg::RUN_KEY, RUN_VALUE)?;
    reg::remove_from_user_path(&dir.to_string_lossy())?;
    reg::delete(GPU_PREFS, &demon.to_string_lossy())?;
    reg::delete_tree(TOAST_KEY)?;
    let me = std::env::current_exe()?;
    let inside = std::fs::canonicalize(&me)
        .ok()
        .zip(std::fs::canonicalize(&dir).ok())
        .is_some_and(|(m, d)| m.starts_with(d));
    if inside {
        // A running exe can't delete itself: let cmd do it once we have exited.
        Command::new("cmd")
            .arg("/C")
            .raw_arg(format!(
                "ping -n 3 127.0.0.1 >NUL & rmdir /S /Q \"{}\"",
                dir.display()
            ))
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .creation_flags(CREATE_NO_WINDOW | DETACHED_PROCESS)
            .spawn()?;
    } else if dir.exists() {
        std::fs::remove_dir_all(&dir).with_context(|| format!("deleting {}", dir.display()))?;
    }
    println!("DiShus is removed from this PC.");
    println!(
        "Your pairings and keys are kept in %APPDATA%\\dishus (and logs in %LOCALAPPDATA%\\dishus)\n\
         in case you install it again. Delete those two folders to remove everything."
    );
    Ok(())
}
