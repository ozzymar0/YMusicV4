//! Who is signed in, as the JVM AccountController keeps it: derived from the engine's cookie-only
//! gate, with the account card cached beside it. The cookies themselves live in the shared jar.

use gpui_kit::*;
use ym_common::log::Logger;
use ym_engine::yt::YtAccountInfo;

use crate::graph::{AppGraph, on_core};
use crate::offline_playlists::ACCOUNT_KEY;
use crate::signin::AccountChanged;

/// The app's one [`AccountController`], for any view to read or observe.
pub struct Accounts(pub Entity<AccountController>);

impl Global for Accounts {}

pub struct AccountController {
    pub signed_in: bool,
    /// `None` while signed out, or when the fetch failed and the cookie gate's answer stands.
    pub account: Option<YtAccountInfo>,
    /// False until the startup probe (or a post-sign-in fetch) finishes.
    pub profile_ready: bool,
    log: Logger,
}

impl EventEmitter<AccountChanged> for AccountController {}

impl AccountController {
    /// Probes the saved jar at once, so a signed-in user comes back signed in.
    pub fn new(cx: &mut Context<Self>) -> Self {
        let log = cx.global::<AppGraph>().log.fork("login");
        let controller = Self { signed_in: false, account: None, profile_ready: false, log };
        controller.probe(cx);
        controller
    }

    pub fn global(cx: &App) -> Entity<AccountController> {
        cx.global::<Accounts>().0.clone()
    }

    fn probe(&self, cx: &mut Context<Self>) {
        let (engine, log) = (cx.global::<AppGraph>().engine(), self.log.clone());
        let offline_account = cx.global::<AppGraph>().settings.string(ACCOUNT_KEY, "").unwrap_or_default();
        let probe = on_core(cx, async move {
            let signed_in = match engine.login_status(&log).await {
                Ok(signed_in) => signed_in,
                Err(error) => {
                    log.warn("startup login-status probe failed", Some(&error));
                    // A failed *network* probe is not a confirmed sign-out. Let the Account tab
                    // render previously saved playlist metadata for the last authenticated user.
                    !offline_account.is_empty()
                }
            };
            if !signed_in {
                return (false, None);
            }
            match engine.account_info(false, &log).await {
                Ok(account) => (true, Some(account)),
                Err(error) => {
                    log.warn("account info fetch failed", Some(&error));
                    (true, None)
                }
            }
        });
        cx.spawn(async move |this, cx| {
            let Some((signed_in, account)) = probe.await else { return };
            this.update(cx, |controller, cx| {
                controller.signed_in = signed_in;
                if account.as_ref().is_some_and(|account| !account.signed_in) {
                    // No account behind the cookies: the jar was revoked remotely.
                    controller.sign_out(cx);
                } else {
                    controller.account = account;
                    if let Some(channel_id) = controller.account.as_ref().and_then(|account| account.channel_id.clone()) {
                        save_offline_identity(cx, channel_id);
                    }
                }
                controller.profile_ready = true;
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// After the sign-in screen's cookie gate passed: re-scrape the session under the new cookies,
    /// pull the account card, then everything reloads under the new identity.
    pub fn on_login_complete(&mut self, cx: &mut Context<Self>) {
        // Switching accounts must not open the previous user's offline playlists.
        save_offline_identity(cx, String::new());
        self.profile_ready = false;
        self.signed_in = true;
        cx.notify();
        let (engine, log) = (cx.global::<AppGraph>().engine(), self.log.clone());
        let fetch = on_core(cx, async move {
            if let Err(error) = engine.refresh_session(&log).await {
                log.warn("post-login session refresh failed", Some(&error));
            }
            match engine.account_info(true, &log).await {
                Ok(account) => Some(account),
                Err(error) => {
                    log.warn("post-login account fetch failed", Some(&error));
                    None
                }
            }
        });
        cx.spawn(async move |this, cx| {
            let account = fetch.await.flatten();
            this.update(cx, |controller, cx| {
                controller.account = account;
                if let Some(channel_id) = controller.account.as_ref().and_then(|account| account.channel_id.clone()) {
                    save_offline_identity(cx, channel_id);
                }
                controller.profile_ready = true;
                cx.notify();
                cx.emit(AccountChanged);
            })
            .ok();
        })
        .detach();
    }

    /// The bundle's logout and an anonymous session refresh; signed out either way.
    pub fn sign_out(&mut self, cx: &mut Context<Self>) {
        save_offline_identity(cx, String::new());
        let (engines, log) = (cx.global::<AppGraph>().engines.clone(), self.log.clone());
        let done = on_core(cx, async move { crate::signin::sign_out(engines, &log).await });
        cx.spawn(async move |this, cx| {
            done.await;
            this.update(cx, |controller, cx| {
                controller.signed_in = false;
                controller.account = None;
                cx.notify();
                cx.emit(AccountChanged);
            })
            .ok();
        })
        .detach();
    }
}

/// Update only an account identifier, never cookies or credentials. Stage synchronously so the UI
/// cannot briefly show the previous account, then commit on a disk worker.
fn save_offline_identity(cx: &App, channel_id: String) {
    let graph = cx.global::<AppGraph>();
    let settings = graph.settings.clone();
    let log = graph.log.fork("offline-playlists");
    settings.stage_raw(ACCOUNT_KEY, channel_id);
    graph.tokio.spawn_blocking(move || {
        if let Err(error) = settings.commit(ACCOUNT_KEY) {
            log.warn(&format!("Could not persist offline account identity: {error}"), None);
        }
    });
}
