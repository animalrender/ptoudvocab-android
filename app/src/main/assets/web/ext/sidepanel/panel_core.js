let currentWord = null,
  currentContext = null,
  currentTranslation = null,
  currentLang = null,
  lastSubtitleText = "",
  lastReviewSubtab = "srs",
  lastSettingsSubtab = "platform",
  currentVideoTime = null,
  currentPlatform = null;
const posCache = {};
let myTabId = null,
  port = null,
  reconnectTimer = null;
function connectToBackground() {
  if (
    document.body.classList.contains("is-mobile-view") ||
    window.location.search.includes("mode=mobile") ||
    ("function" == typeof checkIsMobileUI && checkIsMobileUI())
  )
    console.log(
      "[PV-panel] Standalone mobile view active — skipping background port connection.",
    );
  else {
    reconnectTimer && (clearTimeout(reconnectTimer), (reconnectTimer = null));
    try {
      ((port = chrome.runtime.connect({ name: "primevocab-panel" })),
        port.onMessage.addListener(onPortMessage),
        port.onDisconnect.addListener(() => {
          const e = chrome.runtime.lastError;
          e && e.message && e.message.includes("context invalidated")
            ? console.debug(
                "[PV-panel] Extension context invalidated. Stopping reconnection attempts.",
              )
            : (console.debug("[PV-panel] Port disconnected.", e),
              setStatus(!1),
              (port = null),
              document.body.classList.contains("is-mobile-view") ||
                window.location.search.includes("mode=mobile") ||
                (reconnectTimer = setTimeout(connectToBackground, 5e3)));
        }),
        updatePortTabAssociation());
    } catch (e) {
      (console.debug("[PV-panel] Connection failed:", e),
        setStatus(!1),
        (port = null));
    }
  }
}
function updatePortTabAssociation() {
  !port ||
    document.body.classList.contains("is-mobile-view") ||
    window.location.search.includes("mode=mobile") ||
    chrome.tabs.query({ active: !0, currentWindow: !0 }, (e) => {
      chrome.runtime.lastError;
      if (e && e[0]) {
        const t = e[0].id;
        if (port)
          try {
            port.postMessage({ action: "associate_tab", tabId: t });
          } catch (e) {}
      }
    });
}
function onPortMessage(e) {
  if ("tab_info" === e.action) myTabId = e.tabId;
  else if ("subtitle_update" === e.action)
    (e.platform && (currentPlatform = e.platform),
      renderSubtitle(e.text, e.time),
      setStatus(!0, e.platform));
  else if ("clear_subtitles" === e.action) {
    console.log(
      "[PV-panel] Received clear_subtitles port message from background. Clearing subtitle list UI.",
    );
    const e = document.getElementById("transcript-list"),
      t = document.getElementById("empty-subtitle");
    (e && ((e.innerHTML = ""), (e.style.display = "none")),
      t && (t.style.display = ""));
    const n = document.getElementById("row-translation-popup");
    n && n.remove();
    const o = document.getElementById("source-info-popup");
    (o && o.remove(), (lastSubtitleText = ""), (currentVideoTime = null));
  } else if ("close_panel" === e.action) window.close();
}
const helpBtn = document.getElementById("help-btn");
helpBtn &&
  helpBtn.addEventListener("click", () => {
    ((document.getElementById("shortcuts-overlay").style.display = "flex"),
      "function" == typeof window.refreshChromeCommandsDisplay &&
        window.refreshChromeCommandsDisplay());
  });
const menuShortcutsBtn = document.getElementById("menu-shortcuts-btn");
menuShortcutsBtn &&
  menuShortcutsBtn.addEventListener("click", () => {
    ((document.getElementById("shortcuts-overlay").style.display = "flex"),
      "function" == typeof window.refreshChromeCommandsDisplay &&
        window.refreshChromeCommandsDisplay());
  });
const menuTourBtn = document.getElementById("menu-tour-btn");
function handleLegalLink(e) {
  e.preventDefault();
  const t = (chrome.i18n.getUILanguage() || "en").toLowerCase();
  let n = "#-english-en";
  t.startsWith("tr")
    ? (n = "#-t-rk-e-tr")
    : t.startsWith("de")
      ? (n = "#-deutsch-de")
      : t.startsWith("es")
        ? (n = "#-espa-ol-es")
        : t.startsWith("fr") && (n = "#-fran-ais-fr");
  const o =
    "https://tolgatk.notion.site/PrimeVocab-Product-Landing-Legal-Hub-35d117fa341b4a03ad1ecddb9ac6c522" +
    n;
  chrome.tabs.create({ url: o });
}
(menuTourBtn &&
  menuTourBtn.addEventListener("click", () => {
    window.primevocabOnboarding && window.primevocabOnboarding.start();
  }),
  document.getElementById("shortcuts-close").addEventListener("click", () => {
    document.getElementById("shortcuts-overlay").style.display = "none";
  }),
  document
    .getElementById("shortcuts-overlay")
    .addEventListener("click", (e) => {
      e.target === e.currentTarget && (e.currentTarget.style.display = "none");
    }),
  document
    .getElementById("shortcuts-customize-link")
    .addEventListener("click", (e) => {
      (e.preventDefault(),
        chrome.tabs.create({ url: "chrome://extensions/shortcuts" }));
    }));
const infoMailLink = document.getElementById("info-mail-link");
infoMailLink &&
  infoMailLink.addEventListener("click", (e) => {
    (e.preventDefault(),
      (window.location.href = "mailto:primevocabextension@gmail.com"));
  });
const aboutMailLink = document.getElementById("about-mail-link");
aboutMailLink &&
  aboutMailLink.addEventListener("click", (e) => {
    (e.preventDefault(),
      (window.location.href = "mailto:primevocabextension@gmail.com"));
  });
const infoLegalLink = document.getElementById("info-legal-link");
infoLegalLink && infoLegalLink.addEventListener("click", handleLegalLink);
const aboutLegalLink = document.getElementById("about-legal-link");
aboutLegalLink && aboutLegalLink.addEventListener("click", handleLegalLink);
const infoBtn = document.getElementById("info-btn");
infoBtn &&
  infoBtn.addEventListener("click", () => {
    const e = document.getElementById("info-overlay");
    e && (e.style.display = "flex");
  });
const infoClose = document.getElementById("info-close");
infoClose &&
  infoClose.addEventListener("click", () => {
    const e = document.getElementById("info-overlay");
    e && (e.style.display = "none");
  });
const infoOverlay = document.getElementById("info-overlay");
infoOverlay &&
  infoOverlay.addEventListener("click", (e) => {
    e.target === e.currentTarget && (e.currentTarget.style.display = "none");
  });
let lastRenderedProfilePicKey = null,
  lastRenderedProfileIsPrem = null;
function checkIsMobileUI() {
  const e =
      "mobile" === new URLSearchParams(window.location.search).get("mode"),
    t =
      "undefined" != typeof navigator && navigator.userAgent
        ? navigator.userAgent.toLowerCase()
        : "",
    n = /android|iphone|ipad|ipod|blackberry|windows phone|mobile/i.test(t),
    o = window.innerWidth <= 600;
  if (e || (n && o)) {
    document.body.classList.add("is-mobile-ui", "is-mobile-view");
    const e = document.getElementById("mobile-settings-view");
    e && (e.style.display = "flex");
  } else {
    document.body.classList.remove("is-mobile-ui", "is-mobile-view");
    const e = document.getElementById("mobile-settings-view");
    e && (e.style.display = "none");
  }
}
function switchMainTab(e) {
  if (
    (document
      .querySelectorAll(".tab")
      .forEach((e) => e.classList.remove("active")),
    document
      .querySelectorAll(".panel")
      .forEach((e) => e.classList.remove("active")),
    "settings" === e)
  )
    return void ("function" == typeof openMenuDrawer && openMenuDrawer());
  const t = document.querySelector(`.tab[data-tab="${e}"]`);
  t && t.classList.add("active");
  const n = document.getElementById(`panel-${e}`);
  (n && n.classList.add("active"),
    sessionStorage.setItem("activeMainTab", e),
    "archive" === e && loadArchive(),
    "review" === e && switchReviewSubtab(lastReviewSubtab));
}
const menuBtn =
  document.getElementById("menu-btn") ||
  document.getElementById("settings-btn");
menuBtn &&
  menuBtn.addEventListener("click", () => {
    const e = document.getElementById("menuOverlay");
    e && e.classList.contains("open")
      ? "function" == typeof closeMenuDrawer && closeMenuDrawer()
      : "function" == typeof openMenuDrawer && openMenuDrawer();
  });
const closeSidepanelBtn = document.getElementById("close-sidepanel-btn");
function setStatus(e, t) {
  const n = document.getElementById("status-dot"),
    o = document.getElementById("status-text"),
    i = document.getElementById("hint");
  if (n && o && i)
    if (e) {
      const e = n.classList.contains("connected");
      (n.classList.add("connected"),
        (i.style.display = "flex"),
        t
          ? ((currentPlatform = t),
            (o.textContent =
              "YouTube" === t
                ? getMessage("status_connected_youtube") || "YouTube'a bağlı"
                : "Netflix" === t
                  ? getMessage("status_connected_netflix") || "Netflix'e bağlı"
                  : getMessage("status_connected_prime") ||
                    getMessage("status_connected") ||
                    "Prime Video'ya bağlı"))
          : e ||
            chrome.runtime.sendMessage(
              { action: "get_source_info", tabId: myTabId },
              (e) => {
                let t = "Prime Video";
                (e &&
                  e.url &&
                  (e.url.includes("youtube.com") || e.url.includes("youtu.be")
                    ? (t = "YouTube")
                    : e.url.includes("netflix.com") && (t = "Netflix")),
                  (currentPlatform = t),
                  (o.textContent =
                    "YouTube" === t
                      ? getMessage("status_connected_youtube") ||
                        "YouTube'a bağlı"
                      : "Netflix" === t
                        ? getMessage("status_connected_netflix") ||
                          "Netflix'e bağlı"
                        : getMessage("status_connected_prime") ||
                          getMessage("status_connected") ||
                          "Prime Video'ya bağlı"));
              },
            ));
    } else
      (n.classList.remove("connected"),
        (i.style.display = "none"),
        chrome.tabs.query({ active: !0, currentWindow: !0 }, (e) => {
          chrome.runtime.lastError;
          const t = e && e[0],
            n = t?.url || "";
          ((currentPlatform =
            n.includes("youtube.com") || n.includes("youtu.be")
              ? "YouTube"
              : n.includes("netflix.com")
                ? "Netflix"
                : "Prime Video"),
            (o.textContent =
              getMessage("status_waiting") || "Altyazı bekleniyor..."));
        }));
}
function migrateLegacyStreakIfNeeded() {
  chrome.storage.local.get(
    {
      savedWords: [],
      srsStreakStats: { currentStreak: 0, lastStudyDate: "", bestStreak: 0 },
    },
    ({ savedWords: e, srsStreakStats: t }) => {
      let { currentStreak: n, lastStudyDate: o, bestStreak: i } = t;
      const l = e.reduce((e, t) => Math.max(e, t.streak ?? 0), 0);
      if (!o && l > 0) {
        const e = new Date().toDateString();
        ((n = l),
          (t.currentStreak = l),
          (t.lastStudyDate = e),
          (t.bestStreak = Math.max(i || 0, l)),
          (t.timestamp = Date.now()),
          chrome.storage.local.set({ srsStreakStats: t }, () => {
            console.log(
              "[PV-panel] Migrated legacy streak to srsStreakStats:",
              t,
            );
          }));
      }
    },
  );
}
async function start() {
    if (
    chrome.runtime.getURL("").startsWith("moz-extension://") ||
    ("undefined" != typeof navigator &&
      navigator.userAgent.toLowerCase().includes("firefox"))
  ) {
    const e = document.getElementById("shortcuts-customize-label-row");
    e && (e.style.display = "none");
    const t = document.getElementById("shortcuts-customize-link-row");
    t && (t.style.display = "none");
    const n = document.getElementById("quick-access-settings-group");
    n && (n.style.display = "none");
  }
  (updateArchiveBadge(),
    updateReviewBadge(),
    await new Promise((e) => {
      chrome.storage.sync.get({ settings: {} }, ({ settings: t }) => {
        const n = (function () {
            const e = "undefined" != typeof location ? location.href : "";
            return e.includes("netflix")
              ? "netflix"
              : e.includes("amazon") || e.includes("primevideo")
                ? "prime"
                : "youtube";
          })(),
          o = ((t && t[n]) || t || {}).targetLang || "tr";
        loadSidepanelLanguagePack(o).then(e);
      });
    }),
    loadSettings(),
    console.log("[PV-core] loadSettings completed"),
    initKeymapper(),
    initReviewSubtabs(),
    initSettingsSubtabs(),
    console.log("[PV-core] subtabs and keymapper initialized"));
  const e = sessionStorage.getItem("activeMainTab"),
    t = sessionStorage.getItem("lastReviewSubtab");
  t && (lastReviewSubtab = t);
  const n = sessionStorage.getItem("lastSettingsSubtab");
  n && (lastSettingsSubtab = n);
            if ("undefined" != typeof chrome && chrome.tabs && chrome.tabs.onUpdated)
    try {
      chrome.tabs.onUpdated.addListener((e, t, n) => {
        n && n.active && (detectAndSetPlatform(), updatePortTabAssociation());
      });
    } catch (e) {}
}
(closeSidepanelBtn &&
  closeSidepanelBtn.addEventListener("click", () => {
    window.close();
  }),
  setStatus(!1),
  document.addEventListener("DOMContentLoaded", () => {
    start();
  }),
  chrome.storage.onChanged.addListener(async (e, t) => {
    if ("local" === t) {
      if (e.savedWords) {
        const e = document.querySelector(".tab.active");
        if (e && "archive" === e.dataset.tab)
          "function" == typeof loadArchive && loadArchive();
        else if (e && "review" === e.dataset.tab) {
          const e = document.getElementById("subtab-games");
          if (e && e.classList.contains("active")) {
            const e = document.getElementById("game-play-area");
            (e && "flex" === e.style.display) ||
              "function" != typeof loadGamesHub ||
              loadGamesHub();
          }
        }
      }
    }
  }),
  window.addEventListener("focus", () => {
    ("function" == typeof loadSettings && loadSettings());
  }),
  document.addEventListener("visibilitychange", () => {
    document.hidden ||
      ("function" == typeof loadSettings && loadSettings());
  }));
let sidepanelBubble = null;
const GT_BUBBLE_ICON =
  '<svg width="14" height="14" viewBox="0 0 24 24" style="vertical-align:middle;margin-right:6px;flex-shrink:0" fill="#6366f1" xmlns="http://www.w3.org/2000/svg"><path d="M22.401 4.818h-9.927L10.927 0H1.599C.72 0 .002.719.002 1.599v16.275c0 .878.72 1.597 1.597 1.597h10L13.072 24H22.4c.878 0 1.597-.707 1.597-1.572V6.39c0-.865-.72-1.572-1.597-1.572zm-15.66 8.68c-2.07 0-3.75-1.68-3.75-3.75 0-2.07 1.68-3.75 3.75-3.75 1.012 0 1.86.375 2.512.976l-.99.952a2.194 2.194 0 0 0-1.522-.584c-1.305 0-2.363 1.08-2.363 2.409S5.436 12.16 6.74 12.16c1.507 0 2.13-1.08 2.19-1.808l-2.188-.002V9.066h3.51c.05.23.09.457.09.764 0 2.147-1.434 3.669-3.602 3.669zm16.757 8.93c0 .59-.492 1.072-1.097 1.072h-8.875l3.649-4.03h.005l-.74-2.302.006-.005s.568-.488 1.277-1.24c.712.771 1.63 1.699 2.818 2.805l.771-.772c-1.272-1.154-2.204-2.07-2.89-2.805.919-1.087 1.852-2.455 2.049-3.707h2.034v.002h.002v-.94h-4.532v-1.52h-1.471v1.52H14.3l-1.672-5.21.006.022h9.767c.605 0 1.097.48 1.097 1.072v16.038zm-6.484-7.311c-.536.548-.943.873-.943.873l-.008.004-1.46-4.548h4.764c-.307 1.084-.988 2.108-1.651 2.904-1.176-1.392-1.18-1.844-1.18-1.844h-1.222s.05.678 1.7 2.61z"/></svg>';
function showSidepanelBubble(e, t) {
  (sidepanelBubble ||
    ((sidepanelBubble = document.createElement("div")),
    (sidepanelBubble.id = "sidepanel-selection-bubble"),
    document.body.appendChild(sidepanelBubble)),
    (sidepanelBubble.innerHTML =
      GT_BUBBLE_ICON +
      `<span style="opacity:0.75; font-size:12.5px;">${getMessage("translating_ellipsis") || "Çeviriliyor..."}</span>`),
    (sidepanelBubble.style.display = "flex"));
  const n = t.getBoundingClientRect(),
    o = n.left + window.scrollX + n.width / 2,
    i = n.top + window.scrollY - 8;
  ((sidepanelBubble.style.left = `${o}px`),
    (sidepanelBubble.style.top = `${i}px`));
  const l =
    ("undefined" != typeof currentSettings && currentSettings.targetLang) ||
    "tr";
  chrome.runtime.sendMessage(
    { action: "translate", word: e, targetLang: l },
    (e) => {
      if (sidepanelBubble && "none" !== sidepanelBubble.style.display)
        if (e?.success) {
          sidepanelBubble.innerHTML = GT_BUBBLE_ICON;
          const t = document.createElement("span");
          ((t.textContent = e.translation), sidepanelBubble.appendChild(t));
        } else sidepanelBubble.innerHTML = "<span>⚠️ Çeviri alınamadı</span>";
    },
  );
}
function hideSidepanelBubble() {
  sidepanelBubble && (sidepanelBubble.style.display = "none");
}
(document.addEventListener("mouseup", (e) => {
  "INPUT" === e.target.tagName ||
    "TEXTAREA" === e.target.tagName ||
    e.target.closest(".add-tag-input-container") ||
    (sidepanelBubble && sidepanelBubble.contains(e.target)) ||
    setTimeout(() => {
      const e = window.getSelection(),
        t = e.toString().trim();
      if (t.length > 1) {
        showSidepanelBubble(t, e.getRangeAt(0));
      } else hideSidepanelBubble();
    }, 10);
}),
  (function () {
    const e = document.getElementById("pv-global-tooltip");
    if (!e) return;
    let t = null,
      n = null;
    function o() {
      (clearTimeout(n),
        (n = null),
        (t = null),
        e && (e.classList.remove("show"), (e.style.display = "none")));
    }
    (document.addEventListener(
      "mouseover",
      (i) => {
        const l = i.target.closest(
          "[data-tooltip], [data-i18n-title], [data-pv-tooltip], [title], .cinema-toggle, .t-time, .t-gt, .legend-btn, .ach-badge, .nav-icon, .icon-btn, .close-panel-btn, .speak-btn, .speak-btn-v2, .setting-btn, .lvl-chip, .card-speak-btn, .card-menu-btn, .word-tr",
        );
        if (!l) return void (t && o());
        if (l.hasAttribute("title")) {
          const e = l.getAttribute("title");
          (l.removeAttribute("title"), e && (l.dataset.pvTooltip = e));
        }
        if (l === t) return;
        o();
        const s = (function (e) {
          if (!e || 1 !== e.nodeType) return null;
          if (e.hasAttribute("title")) {
            const t = e.getAttribute("title");
            (e.removeAttribute("title"), t && (e.dataset.pvTooltip = t));
          }
          if (e.getAttribute("data-i18n-title")) {
            const t = getMessage(e.getAttribute("data-i18n-title"));
            if (t) return t;
          }
          return e.dataset.pvTooltip
            ? e.dataset.pvTooltip
            : e.dataset.tooltip
              ? e.dataset.tooltip
              : e._pvTitle
                ? e._pvTitle
                : null;
        })(l);
        s &&
          s.trim() &&
          ((t = l),
          clearTimeout(n),
          (n = setTimeout(() => {
            t === l &&
              ((e.textContent = s),
              (e.style.display = "block"),
              (function (t) {
                const n = t.getBoundingClientRect(),
                  o = e.getBoundingClientRect();
                let i = n.left + n.width / 2,
                  l = n.top;
                (i - o.width / 2 < 10
                  ? (i = o.width / 2 + 10)
                  : i + o.width / 2 > window.innerWidth - 10 &&
                    (i = window.innerWidth - o.width / 2 - 10),
                  l - o.height - 12 < 0
                    ? (e.classList.add("pos-bottom"), (l = n.bottom))
                    : e.classList.remove("pos-bottom"),
                  (e.style.left = `${i}px`),
                  (e.style.top = `${l}px`));
              })(l),
              requestAnimationFrame(() => {
                e.classList.add("show");
              }));
          }, 750)));
      },
      !0,
    ),
      document.addEventListener(
        "mouseout",
        (e) => {
          t && ((e.relatedTarget && t.contains(e.relatedTarget)) || o());
        },
        !0,
      ),
      document.addEventListener("mousedown", o, !0),
      document.addEventListener("click", o, !0),
      window.addEventListener("scroll", o, !0));
  })());
