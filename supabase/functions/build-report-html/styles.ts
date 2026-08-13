// The document's design primitives. Everything the report draws is built from
// the handful of classes below, which is what stops the four apps drifting
// into four different-looking documents.
//
// Deliberately avoids color-mix() and other recent CSS: this HTML is printed
// by WKWebView on iOS and Android's WebView, and older WebViews silently drop
// rules they don't understand, which would show up as an unstyled report.

export const STYLES = `
@page { size: A4; margin: 0; }
* { box-sizing: border-box; }
body {
  margin: 0;
  background: #1B0B2B;
  color: #E4DAEF;
  font-family: -apple-system, "Helvetica Neue", "Roboto", Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -webkit-print-color-adjust: exact;
  print-color-adjust: exact;
}

.page {
  width: 794px;
  min-height: 1123px;
  padding: 48px;
  position: relative;
  page-break-after: always;
}
.page:last-child { page-break-after: auto; }
.page-num {
  position: absolute; bottom: 20px; left: 0; right: 0;
  text-align: center; font-size: 8.5px; color: #A794BD; opacity: .6;
}

/* ---- Section header: blob + icon, title, one-line subtitle ---- */
.sec { display: flex; align-items: center; gap: 12px; margin: 0 0 14px; }
.blob {
  width: 40px; height: 38px; flex: none;
  display: flex; align-items: center; justify-content: center;
  border-radius: 46% 54% 42% 58% / 50% 46% 54% 50%;
}
.blob svg { width: 20px; height: 20px; fill: none; stroke-width: 1.6; }
.sec h2 { margin: 0; font-size: 15px; font-weight: 700; color: #FFFFFF; }
.sec p { margin: 2px 0 0; font-size: 8.5px; color: #A794BD; }
.sec .rule { flex: 1; height: 1px; background: rgba(255,255,255,0.10); margin-left: 4px; }

h3.sub { margin: 18px 0 8px; font-size: 11px; font-weight: 700; letter-spacing: .3px; }

/* ---- Card: one geometry everywhere ---- */
.card {
  background: rgba(255,255,255,0.045);
  border: 1px solid rgba(255,255,255,0.10);
  border-radius: 10px;
  padding: 9px 12px;
  margin-bottom: 7px;
  page-break-inside: avoid;
}
.card.row { display: flex; align-items: center; gap: 10px; }
.card .grow { flex: 1; min-width: 0; }
.card .name { font-size: 11px; font-weight: 600; color: #FFFFFF; }
.card .meta { font-size: 8.5px; color: #A794BD; margin-top: 3px; }
p.meta { font-size: 8.5px; color: #A794BD; }

.badge {
  font-size: 10px; font-weight: 700; border-radius: 999px;
  padding: 3px 9px; white-space: nowrap;
}

.dots { display: flex; gap: 3px; }
.dots i { width: 6px; height: 6px; border-radius: 50%; display: block; }

/* ---- Log rows: label / offset / value ---- */
.log { border-collapse: collapse; width: 100%; }
.log td { padding: 2px 0; vertical-align: top; font-size: 10px; }
.log td.k {
  width: 92px; font-weight: 600; font-size: 8.5px; padding-right: 8px;
  color: #A794BD; letter-spacing: .2px;
}
.log td.o { width: 66px; color: #A794BD; font-size: 8.5px; padding-right: 8px; white-space: nowrap; }
.log td.v { color: #E4DAEF; }
.log tr.note td { color: #A794BD; font-size: 8.5px; font-style: italic; }
.log-head { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 6px; }
.log-head .date { font-size: 11px; font-weight: 700; color: #FFFFFF; }
.log-head .sev { font-size: 10px; font-weight: 700; }

/* ---- Stat strip ---- */
.stats { display: flex; gap: 10px; }
.stats .stat {
  flex: 1; text-align: center; padding: 14px 8px;
  background: rgba(255,255,255,0.045);
  border: 1px solid rgba(255,255,255,0.10);
  border-radius: 12px;
}
.stats .stat b { display: block; font-size: 22px; color: #FFFFFF; font-weight: 700; }
.stats .stat span { font-size: 8.5px; color: #A794BD; }

/* ---- Bars ---- */
.bar { height: 7px; border-radius: 4px; overflow: hidden; }
.bar i { display: block; height: 100%; }

/* ---- Head diagram with heat dots ---- */
.heads { display: flex; gap: 14px; align-items: flex-start; }
.heads figure { margin: 0; position: relative; width: 132px; text-align: center; }
.heads img { width: 132px; display: block; opacity: .92; }
.heads figcaption { font-size: 8.5px; color: #A794BD; margin-top: 2px; }
.heads .dot {
  position: absolute; width: 26px; height: 26px; margin: -13px 0 0 -13px;
  border-radius: 50%; display: flex; align-items: center; justify-content: center;
  border: 1px solid rgba(229,115,115,0.7);
}
.heads .dot b { font-size: 7px; color: #fff; font-weight: 700; }

/* ---- Aura eyes: same construction as AuraDetailSheet (lid + iris behind a
   3x3 zone grid, only hit zones filled) ---- */
.eyes { display: flex; gap: 26px; justify-content: center; padding: 4px 0 2px; }
.eye { text-align: center; }
.eye-box { position: relative; }
.eye-box svg { position: absolute; inset: 0; }
.eye .zones {
  position: absolute; inset: 3px;
  display: grid; grid-template-columns: repeat(3, 1fr); grid-template-rows: repeat(3, 1fr);
}
.eye .zones span {
  margin: 1.5px; border-radius: 5px;
  display: flex; align-items: center; justify-content: center;
  font-size: 7px; color: #FFFFFF; font-weight: 600;
}
.eye .cap { font-size: 8.5px; color: #A794BD; margin-top: 6px; }

/* ---- Attack card: visuals beside the sequence ---- */
.attack { display: flex; gap: 12px; align-items: flex-start; }
.attack-vis { flex: none; width: 140px; }
.attack-body { flex: 1; min-width: 0; }
.mini-head { position: relative; width: 140px; margin: 0; }
.mini-head img { width: 140px; display: block; opacity: .9; border-radius: 8px; }
.mini-head .pin {
  position: absolute; width: 12px; height: 12px; margin: -6px 0 0 -6px;
  border-radius: 50%; border: 1px solid rgba(255,255,255,0.5);
  display: flex; align-items: center; justify-content: center;
  font-size: 7px; font-weight: 700; color: #fff;
}
.curve { width: 100%; display: block; margin-bottom: 4px; }
/* The chart carries 8px of internal padding above its first panel; pulling it
   up by the same amount lines the plot's top edge up with the head image. */
.attack-body .curve { margin-top: -8px; }
.under { display: flex; gap: 14px; align-items: flex-start; margin-top: 4px; }
/* The lists under the chart are reference, not reading matter — tighter rows
   and narrower columns fit an attack's whole log without a second page. */
.under .log td { padding: 0.5px 0; font-size: 7.5px; line-height: 1.3; }
.under .log td.k { width: 54px; font-size: 7.5px; padding-right: 5px; }
.under .log td.o { width: 42px; font-size: 7.5px; padding-right: 5px; }
.under-col { flex: 1; min-width: 0; }
.under-col:first-child { flex: 1.35; }
.under-col.narrow { flex: 1; }
.under-h {
  font-size: 6.5px; letter-spacing: .7px; color: rgba(167,148,189,0.7);
  margin: 0 0 3px;
}
.cat { margin-bottom: 5px; }
.cat-h { font-size: 6.5px; letter-spacing: .6px; color: rgba(167,148,189,0.75); }
.cat-v { font-size: 7.5px; color: #E4DAEF; line-height: 1.35; }
.facts {
  margin-top: 8px; padding-top: 7px;
  border-top: 1px solid rgba(255,255,255,0.08);
  font-size: 8px; color: #A794BD; line-height: 1.55;
}
.facts b { color: #E4DAEF; font-weight: 600; margin-right: 4px; }
.legend { display: flex; flex-wrap: wrap; gap: 3px 12px; margin: 0 0 5px; }
.legend span { font-size: 7px; color: #A794BD; display: flex; align-items: center; gap: 4px; }
.legend span i { width: 6px; height: 6px; border-radius: 50%; display: block; flex: none; }
.legend span em { font-style: normal; color: #E4DAEF; }

.grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.cols { display: flex; align-items: flex-end; gap: 6px; height: 110px; }
/* Frequency packs six charts onto one page, so its columns are shorter and its
   headings tighter than the default. */
.freq { display: grid; grid-template-columns: 1fr 1fr; gap: 0 14px; align-items: start; }
.freq .cols { height: 96px; gap: 6px; overflow: hidden; }
.freq h3.sub { margin: 10px 0 5px; }
.freq .card { padding: 7px 9px; }
.cols div { flex: 1; display: flex; flex-direction: column; justify-content: flex-end; align-items: center; gap: 4px; }
.cols div i { display: block; width: 100%; border-radius: 3px 3px 0 0; }
.cols div span { font-size: 8.5px; color: #A794BD; }

/* ---- Cover ---- */
.cover { min-height: 1027px; display: flex; flex-direction: column; }
.cover .brand { text-align: center; margin-top: 56px; }
/* The mascot sits in the same soft blob the app uses for its card icons,
   rather than a boxed app-icon. */
.cover .logo-blob {
  width: 132px; height: 124px; margin: 0 auto 22px;
  display: flex; align-items: center; justify-content: center;
  border-radius: 46% 54% 42% 58% / 50% 46% 54% 50%;
  background: linear-gradient(135deg, rgba(206,147,216,0.34), rgba(179,136,255,0.14));
  overflow: hidden;
}
.cover .logo-blob img { width: 112px; height: 112px; }
.cover .brand .rule {
  width: 220px; height: 2px; margin: 0 auto 22px;
  background: linear-gradient(90deg, #CE93D8, #FF7BB0);
}
.cover h1 { margin: 0; font-size: 40px; color: #FFFFFF; font-weight: 700; letter-spacing: -.5px; }
.cover h1 span { color: #FF7BB0; }
.cover .kicker { margin: 6px 0 0; font-size: 13px; color: #CE93D8; }
.cover .range { margin: 26px 0 0; font-size: 11px; color: #A794BD; }
.cover .contents { margin-top: 34px; }
.cover .contents h4 {
  margin: 0 0 8px; font-size: 8.5px; color: #A794BD;
  font-weight: 600; letter-spacing: .6px;
}
.cover .contents ol { margin: 0; padding: 0; list-style: none; }
.cover .contents li {
  font-size: 10px; color: #E4DAEF; padding: 4px 0;
  border-bottom: 1px solid rgba(255,255,255,0.10);
  display: flex; justify-content: space-between;
}
.cover .contents li em { color: #A794BD; font-style: normal; }
.cover .patient {
  margin-top: auto; padding: 14px 16px;
  border: 1px solid rgba(255,255,255,0.10); border-radius: 12px;
  background: rgba(255,255,255,0.045);
  display: flex; gap: 26px; font-size: 10px;
}
.cover .patient div span { display: block; color: #A794BD; font-size: 8.5px; margin-bottom: 2px; }
.disclaimer { margin-top: 12px; font-size: 7.5px; color: #A794BD; line-height: 1.5; }
`;
