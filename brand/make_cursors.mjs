// Mercan imleçleri — hazır SVG paketinden (Fluent UI System Icons, MIT) alınan siluetlere
// Mercan görünümü uygulanır: renk geçişli gövde, kalın beyaz kontur, altında yumuşak gölge.
// İki renk üretilir: normal (turuncu) ve model düşünürken kullanılan mavi (-blue).
//
//   node make_cursors.mjs <hedef-klasör> [<hedef-klasör2> ...]
//
// Kaynak SVG'ler cursors/ klasöründe (bkz. cursors/NOTICE.txt). Metin imleci (I-beam) hazır
// paketlerde bulunmadığı için burada basit bir dikdörtgen-serif çizimi olarak üretilir.
// Yazdırdığı sıcak nokta (hotspot) değerleri mercan.css ve windows/ui/splash.html'e girer.
import { Resvg } from '@resvg/resvg-js';
import fs from 'node:fs';
import path from 'node:path';

const HEIGHT = 18; // imlecin görünür yüksekliği (1x piksel) — Windows'un kendi oku ~20px
const PAD = 7; // gölge + çok hafif parıltı için her kenardan boşluk
const STROKE = 1.9; // beyaz konturun kalınlığı
const MAX = 128; // Chromium bundan büyük imleci yok sayar (2x dosya dâhil)

// Gövde geçişi ve gölge; parıltı yok (imlecin etrafındaki neon halka hoş durmuyordu).
const THEMES = {
	'': { light: '#FFA842', deep: '#EE5E08', glow: '#FF8A2B' }, // normal
	'-blue': { light: '#3D7BD1', deep: '#194891', glow: '#3E86DE' } // model düşünürken
};
const SHADOW = { dx: 0.5, dy: 0.9, sd: 1.2, color: '#2A1204', opacity: 0.38 };
// Arkada çok hafif bir parıltı: şekli zeminden ayırır, halka gibi durmaz.
const GLOW = { sd: 2.6, opacity: 0.3 };

const here = path.dirname(new URL(import.meta.url).pathname);

// Sıcak nokta: şeklin sınır kutusuna göre oran + konturun dışarı taştığı pay (1x piksel).
const CURSORS = {
	arrow: { file: 'cursors/fluent-cursor.svg', hot: [0, 0], nudge: [0.5, 0.5] },
	hand: { file: 'cursors/fluent-hand-point.svg', hot: [0.5, 0], nudge: [0, -0.5] },
	text: { beam: true, hot: [0.5, 0.5], nudge: [0, 0] }
};

// Metin imleci: ince gövde + üstte/altta serif (24x24 kutu içinde).
const BEAM = [
	'M10.6 1.2h2.8a1.2 1.2 0 0 1 0 2.4h-2.8a1.2 1.2 0 0 1 0-2.4z',
	'M10.8 2.4h2.4v19.2h-2.4z',
	'M10.6 20.4h2.8a1.2 1.2 0 0 1 0 2.4h-2.8a1.2 1.2 0 0 1 0-2.4z',
	'M8.4 1.2h7.2a1.1 1.1 0 0 1 0 2.2H8.4a1.1 1.1 0 0 1 0-2.2z',
	'M8.4 20.6h7.2a1.1 1.1 0 0 1 0 2.2H8.4a1.1 1.1 0 0 1 0-2.2z'
];

const readPaths = (file) => {
	const src = fs.readFileSync(path.join(here, file), 'utf8');
	const box = src.match(/viewBox="([^"]+)"/);
	const paths = [...src.matchAll(/<path[^>]*\sd="([^"]+)"/g)].map((m) => m[1]);
	if (!box || !paths.length) throw new Error(`şekil bulunamadı: ${file}`);
	return { viewBox: box[1].trim().split(/[\s,]+/).map(Number), paths };
};

const build = (shape, theme) => {
	const [vx, vy, vw, vh] = shape.viewBox;
	const body = shape.paths.map((d) => `<path d="${d}"/>`).join('');
	// Simgenin kendi kutusu içindeki gerçek sınırları.
	const bb = new Resvg(
		`<svg xmlns="http://www.w3.org/2000/svg" viewBox="${vx} ${vy} ${vw} ${vh}">${body}</svg>`
	).getBBox();
	const k = HEIGHT / bb.height;
	const w = Math.ceil(bb.width * k + 2 * PAD);
	const h = Math.ceil(HEIGHT + 2 * PAD);
	const tx = PAD - bb.x * k;
	const ty = PAD - bb.y * k;
	const place = `translate(${tx.toFixed(2)} ${ty.toFixed(2)}) scale(${k.toFixed(4)})`;
	const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="${theme.light}"/><stop offset="1" stop-color="${theme.deep}"/>
    </linearGradient>
    <filter id="shadow" x="-90%" y="-90%" width="280%" height="280%">
      <feDropShadow dx="0" dy="0" stdDeviation="${GLOW.sd}" flood-color="${theme.glow}" flood-opacity="${GLOW.opacity}"/>
      <feDropShadow dx="${SHADOW.dx}" dy="${SHADOW.dy}" stdDeviation="${SHADOW.sd}" flood-color="${SHADOW.color}" flood-opacity="${SHADOW.opacity}"/>
    </filter>
  </defs>
  <g filter="url(#shadow)" transform="${place}">
    <g fill="none" stroke="#FFFFFF" stroke-width="${((STROKE * 2) / k).toFixed(3)}" stroke-linejoin="round" stroke-linecap="round">${body}</g>
    <g fill="#FFFFFF">${body}</g>
  </g>
  <g transform="${place}" fill="url(#g)">${body}</g>
</svg>`;
	return { svg, w, h, bw: bb.width * k, bh: HEIGHT };
};

const targets = process.argv.slice(2);
if (!targets.length) targets.push('.');

for (const [name, cfg] of Object.entries(CURSORS)) {
	const shape = cfg.beam ? { viewBox: [0, 0, 24, 24], paths: BEAM } : readPaths(cfg.file);
	let size = '';
	for (const [suffix, theme] of Object.entries(THEMES)) {
		const { svg, w, h, bw, bh } = build(shape, theme);
		if (Math.max(w, h) * 2 > MAX) throw new Error(`${name}: ${w}x${h} — 2x dosya ${MAX}px sınırını aşıyor`);
		size = `${w}x${h}`;
		for (const scale of [1, 2]) {
			const png = new Resvg(svg, { fitTo: { mode: 'width', value: w * scale } }).render().asPng();
			for (const target of targets) {
				fs.mkdirSync(target, { recursive: true });
				fs.writeFileSync(path.join(target, `cursor-${name}${suffix}${scale === 2 ? '-2x' : ''}.png`), png);
			}
		}
		if (suffix === '') {
			const hx = Math.round(PAD + cfg.hot[0] * bw + cfg.nudge[0]);
			const hy = Math.round(PAD + cfg.hot[1] * bh + cfg.nudge[1]);
			console.log(`cursor-${name}.png (+ -blue, +2x)  ${size}  sıcak nokta ${hx} ${hy}`);
		}
	}
}
