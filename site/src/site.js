/* RideFlux website — the only script on the page.
   1. First visit to the English home page: send the visitor to their own language, once.
   2. Remember an explicit language choice so the redirect never fights it.
   3. Close the language / menu popovers on outside click or Escape.
   No cookies, no network requests, nothing leaves the browser. */
(function () {
  "use strict";

  var KEY = "rideflux-lang";
  var root = document.documentElement;

  function get() { try { return window.localStorage.getItem(KEY); } catch (e) { return null; } }
  function set(v) { try { window.localStorage.setItem(KEY, v); } catch (e) { /* storage blocked: fine */ } }

  // Map a BCP-47 tag from the browser onto one of the site's languages.
  function match(tag, supported) {
    tag = String(tag || "").replace("_", "-");
    var lower = tag.toLowerCase();
    var i;
    for (i = 0; i < supported.length; i++) {
      if (supported[i].toLowerCase() === lower) return supported[i];
    }
    if (lower === "in") return "id";                                        // legacy Indonesian code
    if (lower.indexOf("zh") === 0) {
      return /-(tw|hk|mo|hant)/.test(lower) ? "zh-TW" : "zh-CN";           // Traditional vs Simplified
    }
    var base = lower.split("-")[0];
    for (i = 0; i < supported.length; i++) {
      if (supported[i].toLowerCase() === base) return supported[i];
    }
    return null;
  }

  // 1. Redirect (English home page only; the build sets data-autolang there).
  if (root.getAttribute("data-autolang") === "1" && !get()) {
    var supported = (root.getAttribute("data-supported") || "").split(",");
    var prefs = (navigator.languages && navigator.languages.length) ? navigator.languages : [navigator.language];
    for (var p = 0; p < prefs.length; p++) {
      var hit = match(prefs[p], supported);
      if (hit === "en") break;                      // English is first choice: stay
      if (hit) {
        var target = root.getAttribute("data-home-" + hit);
        if (target) { window.location.replace(target); }
        break;
      }
    }
  }

  // 2. Remember an explicit choice.
  document.addEventListener("click", function (ev) {
    var a = ev.target.closest ? ev.target.closest("a[data-lang-choice]") : null;
    if (a) set(a.getAttribute("data-lang-choice"));
  });

  // 3. Popovers.
  function closeOthers(except) {
    var open = document.querySelectorAll("details.lang[open], details.menu[open]");
    for (var i = 0; i < open.length; i++) if (open[i] !== except) open[i].removeAttribute("open");
  }
  document.addEventListener("click", function (ev) {
    var d = ev.target.closest ? ev.target.closest("details.lang, details.menu") : null;
    closeOthers(d);
  });
  document.addEventListener("keydown", function (ev) {
    if (ev.key === "Escape") closeOthers(null);
  });
})();
