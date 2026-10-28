// Schedule parser: port of ScheduleParser.java. Glyph = {x, w, y (baseline, top-down), h (font size), s}
var Sched = (function () {
  var LABEL = /^\s*(?:(\d{1,2})\s*(?:\)|-?\s*(?:я\s*)?пара|\.(?!\d))\s*)?(\d{1,2})[.:](\d{2})\s*[-–—]\s*(\d{1,2})[.:](\d{2})/i;
  var TIME = /(\d{1,2})[.:](\d{2})\s*[-–—]\s*(\d{1,2})[.:](\d{2})/;
  var TIME_G = /(\d{1,2})[.:](\d{2})\s*[-–—]\s*(\d{1,2})[.:](\d{2})/g;
  var NUM = /^\s*(\d{1,2})\s*(?:\)|-?\s*(?:я\s*)?пара|\.(?!\d))/i;
  var GROUP_TOKEN = /^[0-9][0-9А-ЯЁA-Zа-яё.\-\/]*$/;
  var SPEC = /\d{2}\.\d{2}\.\d{2}/;
  var SPEC_G = /\d{2}\.\d{2}\.\d{2}/g;
  var DATE = /(\d{1,2}\.\d{1,2}\.\d{2,4})/;
  var COURSE = /(\d)\s*курс/i;
  var DAY = /(понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)/i;
  var NAME = /^[А-ЯЁ][а-яё]+(?:-[А-ЯЁ][а-яё]+)?(?:\s+[А-ЯЁ]\.\s?(?:[А-ЯЁ]\.?)?)?$/;

  function blank(s) { return s.trim() === ""; }

  function collides(line, g) {
    for (var i = 0; i < line.g.length; i++) {
      var o = line.g[i];
      if (blank(o.s)) continue;
      var ov = Math.min(o.x + o.w, g.x + g.w) - Math.max(o.x, g.x);
      if (ov > Math.min(o.w, g.w) * 0.35) return true;
    }
    return false;
  }

  function lines(glyphs) {
    var gs = glyphs.slice().sort(function (a, b) { return a.y - b.y; });
    var out = [];
    gs.forEach(function (g) {
      var target = null;
      for (var i = out.length - 1; i >= 0 && i >= out.length - 4; i--) {
        var l = out[i];
        if (Math.abs(g.y - l.y) > Math.max(2, l.h * 0.3)) continue;
        if (blank(g.s) || !collides(l, g)) { target = l; break; }
      }
      if (target) target.g.push(g); else out.push({ y: g.y, h: g.h, g: [g] });
    });
    out.forEach(function (l) {
      l.g.sort(function (a, b) { return a.x - b.x; });
      var text = "", map = [], prev = null;
      l.g.forEach(function (g, i) {
        if (prev && g.x - (prev.x + prev.w) > Math.max(1.2, g.h * 0.18) && prev.s !== " " && g.s !== " ") { text += " "; map.push(-1); }
        for (var k = 0; k < g.s.length; k++) { text += g.s[k]; map.push(i); }
        prev = g;
      });
      l.text = text; l.map = map;
    });
    return out;
  }

  function segments(l, gap) {
    var out = [], sb = "", x0 = 0, x1 = 0, prev = null;
    l.g.forEach(function (g) {
      if (!prev || g.x - (prev.x + prev.w) > gap) {
        if (prev) out.push([x0, x1, sb.trim()]);
        sb = ""; x0 = g.x;
      } else if (g.x - (prev.x + prev.w) > Math.max(1.2, g.h * 0.18) && prev.s !== " " && g.s !== " ") sb += " ";
      sb += g.s; x1 = g.x + g.w; prev = g;
    });
    if (prev) out.push([x0, x1, sb.trim()]);
    return out.filter(function (o) { return o[2] !== ""; });
  }

  function clean(s) { return s.replace(/\s+/g, " ").trim(); }

  function parsePage(glyphs, known) {
    var sheet = { date: "", weekday: "", course: "", columns: [] };
    var ls = lines(glyphs);
    ls.forEach(function (l) {
      var m;
      if (!sheet.date && l.text.toLowerCase().indexOf("расписание") >= 0 && (m = DATE.exec(l.text))) sheet.date = m[1];
      if (!sheet.weekday && (m = DAY.exec(l.text))) sheet.weekday = m[1].toLowerCase();
      if (!sheet.course && (m = COURSE.exec(l.text))) sheet.course = m[1];
    });

    var labels = [], proto = [], labelRight = 0;
    ls.forEach(function (l) {
      var m = LABEL.exec(l.text);
      if (!m) return;
      var end = m[0].length - 1;
      while (end > 0 && l.map[end] < 0) end--;
      var last = l.g[l.map[end]];
      labelRight = Math.max(labelRight, last.x + last.w);
      labels.push(l.y);
      proto.push({ num: m[1] ? +m[1] : proto.length + 1, time: m[2] + ":" + m[3] + "–" + m[4] + ":" + m[5] });
    });
    if (!labels.length) return sheet;

    var header = null, spec = null, i;
    ls.forEach(function (l) { if (l.y < labels[0] && /^\s*Пара/.test(l.text)) header = l; });
    for (i = 0; i < ls.length; i++) {
      var l = ls[i];
      if (l.y >= labels[0] || (header && l.y <= header.y)) continue;
      if (SPEC.test(l.text) && l.text.toLowerCase().indexOf("расписание") < 0) { spec = l; break; }
    }
    if (!header) {
      for (i = 0; i < ls.length; i++) if (ls[i].y < labels[0] && /\d{3,4}[А-ЯЁA-Z].*\d{3,4}[А-ЯЁA-Z]/.test(ls[i].text)) { header = ls[i]; break; }
    }
    if (!header) return sheet;
    var left = labelRight + 1;

    var centers = [], specs = [];
    if (spec) segments(spec, 8).forEach(function (s) {
      if (SPEC.test(s[2]) && s[1] > left) { centers.push((s[0] + s[1]) / 2); specs.push(s[2]); }
    });
    var hTop = header.y - header.h * 1.4, hBot = spec ? spec.y - spec.h * 0.6 : header.y + header.h * 1.4;
    var headSegs = [];
    ls.forEach(function (l) {
      if (l.y >= hTop && l.y <= hBot && l.h <= header.h * 1.4 && l.text.toLowerCase().indexOf("курс") < 0)
        segments(l, 8).forEach(function (s) { if (s[1] > left && s[2].indexOf("Пара") !== 0) headSegs.push(s); });
    });
    if (!centers.length) {
      headSegs.sort(function (a, b) { return a[0] - b[0]; });
      var cx0 = -1, cx1 = -1;
      headSegs.forEach(function (s) {
        if (cx0 < 0) { cx0 = s[0]; cx1 = s[1]; return; }
        if (s[0] - cx1 > 24) { centers.push((cx0 + cx1) / 2); specs.push(""); cx0 = s[0]; }
        cx1 = Math.max(cx1, s[1]);
      });
      if (cx0 >= 0) { centers.push((cx0 + cx1) / 2); specs.push(""); }
    }
    var nc = centers.length, lo = [], hi = [];
    for (i = 0; i < nc; i++) {
      lo[i] = i === 0 ? left : (centers[i - 1] + centers[i]) / 2;
      hi[i] = i === nc - 1 ? Infinity : (centers[i] + centers[i + 1]) / 2;
      var col = { groups: [], spec: specs[i], lessons: [] }, sb = "";
      headSegs.forEach(function (s) { var cx = (s[0] + s[1]) / 2; if (cx >= lo[i] && cx < hi[i]) sb += s[2] + ","; });
      col.groups = groupTokens(sb);
      sheet.columns.push(col);
    }

    var nr = labels.length, top = [], bot = [];
    var firstTop = (spec ? spec.y : header.y) + 2;
    for (var r = 0; r < nr; r++) {
      top[r] = r === 0 ? firstTop : bot[r - 1];
      bot[r] = r === nr - 1 ? labels[r] + Math.max(20, labels[r] - top[r]) : (labels[r] + labels[r + 1]) / 2;
    }

    for (var ci = 0; ci < nc; ci++) {
      for (r = 0; r < nr; r++) {
        var cell = glyphs.filter(function (g) { var cx = g.x + g.w / 2; return cx >= lo[ci] && cx < hi[ci] && g.y > top[r] && g.y <= bot[r] && !blank(g.s); });
        if (!cell.length) continue;
        var texts = lines(cell).map(function (l) { return clean(l.text); });
        var sp = split(texts, known), subject = sp.subject, teachers = sp.teachers;
        if (!subject && !teachers.length) continue;
        sheet.columns[ci].lessons.push({ num: proto[r].num, time: proto[r].time, subject: subject, teachers: teachers });
      }
    }
    return sheet;
  }


  function split(texts, known) {
    var teachers = [], cut = texts.length;
    for (var t = texts.length - 1; t >= 0; t--) {
      var items = texts[t].split(/[,;]/), nameLine = true, isKnown = true, names = [];
      for (var k = 0; k < items.length; k++) {
        var it = items[k].trim(); if (!it) continue;
        if (!NAME.test(it)) { nameLine = false; break; }
        names.push(it);
        if (!known[it.split(/\s+/)[0]]) isKnown = false;
      }
      if (!nameLine || !names.length) break;
      if (t !== texts.length - 1 && !isKnown) break;
      if (t === texts.length - 1 && texts.length === 1 && !isKnown) break;
      for (k = names.length - 1; k >= 0; k--) if (teachers.indexOf(names[k]) < 0) teachers.unshift(names[k]);
      cut = t;
    }
    var subj = [];
    texts.slice(0, cut).forEach(function (x) { if (subj.indexOf(x) < 0) subj.push(x); });
    return { subject: clean(subj.join(" ")).replace(/[,;]$/, ""), teachers: teachers };
  }

  function groupTokens(text) {
    var out = [];
    text.split(/[,;\s]+/).forEach(function (t) {
      t = t.trim();
      if (t.length < 3 || /^\d{2}\.\d{2}\.\d{2}$/.test(t) || /^\d{1,2}\.\d{1,2}\.\d{2,4}$/.test(t) || TIME.test(t)) return;
      if (GROUP_TOKEN.test(t) && (/\d{3,}/.test(t) || /\d.*[А-ЯЁA-Zа-яё]/.test(t))) out.push(t);
    });
    return out;
  }

  function titleInfo(sheet, ls) {
    ls.forEach(function (l) {
      var m;
      if (!sheet.date && l.text.toLowerCase().indexOf("расписание") >= 0 && (m = DATE.exec(l.text))) sheet.date = m[1];
      if (!sheet.weekday && (m = DAY.exec(l.text))) sheet.weekday = m[1].toLowerCase();
      if (!sheet.course && (m = COURSE.exec(l.text))) sheet.course = m[1];
    });
    if (!sheet.date) for (var i = 0; i < ls.length; i++) { var m2 = DATE.exec(ls[i].text); if (m2 && !SPEC.test(ls[i].text)) { sheet.date = m2[1]; break; } }
  }

  // ===================== grid-based parsing =====================
  function addRect(out, x0, y0, x1, y1, stroked) {
    var l = Math.min(x0, x1), r = Math.max(x0, x1), t = Math.min(y0, y1), b = Math.max(y0, y1), w = r - l, h = b - t;
    if (h <= 3 && w > 3) out.push({ h: true, pos: (t + b) / 2, a: l, b: r });
    else if (w <= 3 && h > 3) out.push({ h: false, pos: (l + r) / 2, a: t, b: b });
    else if (stroked && w > 3 && h > 3) {
      out.push({ h: true, pos: t, a: l, b: r }, { h: true, pos: b, a: l, b: r }, { h: false, pos: l, a: t, b: b }, { h: false, pos: r, a: t, b: b });
    }
  }
  function addLine(out, x0, y0, x1, y1) {
    if (Math.abs(y1 - y0) < 1.5 && Math.abs(x1 - x0) > 3) out.push({ h: true, pos: (y0 + y1) / 2, a: Math.min(x0, x1), b: Math.max(x0, x1) });
    else if (Math.abs(x1 - x0) < 1.5 && Math.abs(y1 - y0) > 3) out.push({ h: false, pos: (x0 + x1) / 2, a: Math.min(y0, y1), b: Math.max(y0, y1) });
  }
  function cluster(v, tol) {
    v = v.slice().sort(function (a, b) { return a - b; });
    var out = [], sum = 0, n = 0, last = NaN;
    v.forEach(function (x) { if (n && x - last > tol) { out.push(sum / n); sum = 0; n = 0; } sum += x; n++; last = x; });
    if (n) out.push(sum / n);
    return out;
  }
  function coverage(segs, horiz, pos, a, b, tol) {
    var iv = [];
    segs.forEach(function (s) { if (s.h === horiz && Math.abs(s.pos - pos) <= tol) { var lo = Math.max(a, s.a), hi = Math.min(b, s.b); if (hi > lo) iv.push([lo, hi]); } });
    iv.sort(function (p, q) { return p[0] - q[0]; });
    var cov = 0, cl = -1, ch = -1;
    iv.forEach(function (i) { if (ch < 0 || i[0] > ch) { if (ch > 0) cov += ch - cl; cl = i[0]; ch = i[1]; } else ch = Math.max(ch, i[1]); });
    if (ch > 0) cov += ch - cl;
    return b > a ? cov / (b - a) : 0;
  }
  function cells(segs, glyphs) {
    var xs = cluster(segs.filter(function (s) { return !s.h; }).map(function (s) { return s.pos; }), 2.5);
    var ys = cluster(segs.filter(function (s) { return s.h; }).map(function (s) { return s.pos; }), 2.5);
    var nx = xs.length - 1, ny = ys.length - 1;
    if (nx < 2 || ny < 2 || nx * ny > 20000) return null;
    var parent = []; for (var i = 0; i < nx * ny; i++) parent[i] = i;
    function find(i) { while (parent[i] !== i) { parent[i] = parent[parent[i]]; i = parent[i]; } return i; }
    for (var j = 0; j < ny; j++) for (i = 0; i < nx; i++) {
      if (i + 1 < nx && coverage(segs, false, xs[i + 1], ys[j], ys[j + 1], 2.5) < 0.5) parent[find(j * nx + i)] = find(j * nx + i + 1);
      if (j + 1 < ny && coverage(segs, true, ys[j + 1], xs[i], xs[i + 1], 2.5) < 0.5) parent[find(j * nx + i)] = find((j + 1) * nx + i);
    }
    var map = {}, out = [];
    for (j = 0; j < ny; j++) for (i = 0; i < nx; i++) {
      var root = find(j * nx + i), c = map[root];
      if (!c) { c = map[root] = { x0: xs[i], x1: xs[i + 1], y0: ys[j], y1: ys[j + 1], col0: i, g: [], texts: [], text: "" }; out.push(c); }
      else { c.x0 = Math.min(c.x0, xs[i]); c.x1 = Math.max(c.x1, xs[i + 1]); c.y0 = Math.min(c.y0, ys[j]); c.y1 = Math.max(c.y1, ys[j + 1]); c.col0 = Math.min(c.col0, i); }
    }
    glyphs.forEach(function (g) {
      if (blank(g.s)) return;
      var cx = g.x + g.w / 2, cy = g.y - g.h * 0.3;
      for (var k = 0; k < out.length; k++) { var c = out[k]; if (cx >= c.x0 && cx < c.x1 && cy >= c.y0 && cy < c.y1) { c.g.push(g); break; } }
    });
    out.forEach(function (c) { lines(c.g).forEach(function (l) { var t = clean(l.text); if (t) c.texts.push(t); }); c.text = c.texts.join(" "); });
    return out;
  }

  function parseGrid(glyphs, segs, known) {
    if (!segs || segs.length < 6) return null;
    var cs = cells(segs, glyphs);
    if (!cs) return null;
    var sheet = { date: "", weekday: "", course: "", columns: [] };
    titleInfo(sheet, lines(glyphs));
    var minCol = Infinity;
    cs.forEach(function (c) { if (TIME.test(c.text)) minCol = Math.min(minCol, c.col0); });
    var labels = cs.filter(function (c) { return c.col0 === minCol && TIME.test(c.text); }).sort(function (a, b) { return a.y0 - b.y0; });
    if (!labels.length) return null;
    var labelRight = 0; labels.forEach(function (c) { labelRight = Math.max(labelRight, c.x1); });
    var lastY = labels[labels.length - 1].y0;
    var heads = cs.filter(function (c) {
      if (c.x0 < labelRight - 1 || c.y1 > lastY + 1) return false;
      for (var i = 0; i < labels.length; i++) if (c.y0 >= labels[i].y0 - 1 && c.y1 <= labels[i].y1 + 1) return false;
      return groupTokens(c.text).length > 0;
    });
    if (!heads.length) return null;
    var byKey = {}, order = [];
    labels.forEach(function (lab, idx) {
      var tm = TIME.exec(lab.text), nm = NUM.exec(lab.text);
      var num = nm ? +nm[1] : idx + 1, time = tm[1] + ":" + tm[2] + "–" + tm[3] + ":" + tm[4];
      var ref = null;
      heads.forEach(function (h) { if (h.y1 <= lab.y0 + 1 && (!ref || h.y1 > ref.y1)) ref = h; });
      if (!ref) return;
      var band = heads.filter(function (h) { return h.y1 <= lab.y0 + 1 && h.y0 < ref.y1 - 1 && h.y1 > ref.y0 + 1; }).sort(function (a, b) { return a.x0 - b.x0; });
      band.forEach(function (h) {
        var key = Math.round(h.x0) + ":" + Math.round(h.y0), col = byKey[key];
        if (!col) {
          col = byKey[key] = { groups: groupTokens(h.text), spec: "", lessons: [] }; order.push(key);
          for (var i = 0; i < cs.length; i++) { var s2 = cs[i]; if (s2.y0 >= h.y1 - 1 && s2.y1 <= lab.y0 + 1 && s2.x0 >= h.x0 - 1 && s2.x1 <= h.x1 + 1) { var sm = SPEC.exec(s2.text); if (sm) { col.spec = sm[0]; break; } } }
        }
        var parts = cs.filter(function (c) {
          if (c.y1 <= lab.y0 + 1 || c.y0 >= lab.y1 - 1 || !c.texts.length) return false;
          var ov = Math.min(c.x1, h.x1) - Math.max(c.x0, h.x0);
          return ov >= Math.min(c.x1 - c.x0, h.x1 - h.x0) * 0.5;
        }).sort(function (a, b) { return Math.abs(a.y0 - b.y0) > 1 ? a.y0 - b.y0 : a.x0 - b.x0; });
        var texts = []; parts.forEach(function (c) { texts = texts.concat(c.texts); });
        if (!texts.length) return;
        var sp = split(texts, known);
        if (!sp.subject && !sp.teachers.length) return;
        col.lessons.push({ num: num, time: time, subject: sp.subject, teachers: sp.teachers });
      });
    });
    order.forEach(function (k) { if (byKey[k].groups.length) sheet.columns.push(byKey[k]); });
    var n = 0; sheet.columns.forEach(function (c) { n += c.lessons.length; });
    return n ? sheet : null;
  }

  function parse(glyphs, segs, known) {
    var g = null;
    try { g = parseGrid(glyphs, segs, known); } catch (e) {}
    return g || parsePage(glyphs, known);
  }

  /** Table borders from a pdf.js operator list, in the same top-down coordinates as the text. */
  function bordersFromOps(opList, viewport) {
    var O = pdfjsLib.OPS, U = pdfjsLib.Util, segs = [];
    var ctm = [1, 0, 0, 1, 0, 0], stack = [], rects = [], lns = [], boxes = [], box = null, cx = 0, cy = 0, sx = 0, sy = 0;
    function dev(x, y) { var p = U.applyTransform([x, y], ctm); return U.applyTransform(p, viewport.transform); }
    function grow(p) { if (!box) { box = [p[0], p[1], p[0], p[1]]; boxes.push(box); return; } box[0] = Math.min(box[0], p[0]); box[1] = Math.min(box[1], p[1]); box[2] = Math.max(box[2], p[0]); box[3] = Math.max(box[3], p[1]); }
    function clear() { rects = []; lns = []; boxes = []; box = null; }
    function commit(stroked) {
      rects.forEach(function (r) { addRect(segs, r[0], r[1], r[2], r[3], stroked); });
      if (stroked) lns.forEach(function (l) { addLine(segs, l[0], l[1], l[2], l[3]); });
      else boxes.forEach(function (b) { addRect(segs, b[0], b[1], b[2], b[3], false); });
      clear();
    }
    var fn = opList.fnArray, args = opList.argsArray;
    for (var i = 0; i < fn.length; i++) {
      var op = fn[i], a = args[i];
      if (op === O.save) stack.push(ctm.slice());
      else if (op === O.restore) { if (stack.length) ctm = stack.pop(); }
      else if (op === O.transform) ctm = U.transform(ctm, a);
      else if (op === O.paintFormXObjectBegin) { stack.push(ctm.slice()); if (a && a[0]) ctm = U.transform(ctm, a[0]); }
      else if (op === O.paintFormXObjectEnd) { if (stack.length) ctm = stack.pop(); }
      else if (op === O.constructPath) {
        var ops = a[0], co = a[1], k = 0;
        for (var j = 0; j < ops.length; j++) {
          var o = ops[j];
          if (o === O.rectangle) {
            var x = co[k++], y = co[k++], w = co[k++], h = co[k++];
            var p0 = dev(x, y), p1 = dev(x + w, y + h), p2 = dev(x + w, y), p3 = dev(x, y + h);
            rects.push([Math.min(p0[0], p1[0], p2[0], p3[0]), Math.min(p0[1], p1[1], p2[1], p3[1]), Math.max(p0[0], p1[0], p2[0], p3[0]), Math.max(p0[1], p1[1], p2[1], p3[1])]);
            box = null; cx = sx = x; cy = sy = y;
          } else if (o === O.moveTo) { cx = sx = co[k++]; cy = sy = co[k++]; box = null; grow(dev(cx, cy)); }
          else if (o === O.lineTo) { var nx = co[k++], ny = co[k++], q0 = dev(cx, cy), q1 = dev(nx, ny); lns.push([q0[0], q0[1], q1[0], q1[1]]); grow(q1); cx = nx; cy = ny; }
          else if (o === O.curveTo) { k += 4; cx = co[k++]; cy = co[k++]; }
          else if (o === O.curveTo2 || o === O.curveTo3) { k += 2; cx = co[k++]; cy = co[k++]; }
          else if (o === O.closePath) { if (cx !== sx || cy !== sy) { var r0 = dev(cx, cy), r1 = dev(sx, sy); lns.push([r0[0], r0[1], r1[0], r1[1]]); } cx = sx; cy = sy; }
        }
      }
      else if (op === O.stroke || op === O.closeStroke || op === O.fillStroke || op === O.eoFillStroke || op === O.closeFillStroke || op === O.closeEOFillStroke) commit(true);
      else if (op === O.fill || op === O.eoFill) commit(false);
      else if (op === O.endPath) clear();
    }
    return segs;
  }

  /** pdf.js page -> glyphs (one per character, positioned proportionally inside each text run). */
  function glyphsFromContent(content, viewport) {
    var out = [];
    content.items.forEach(function (it) {
      if (!it.str) return;
      var tx = pdfjsLib.Util.transform(viewport.transform, it.transform);
      var size = Math.hypot(tx[2], tx[3]) || it.height || 10;
      var x = tx[4], y = tx[5];
      var width = it.width * viewport.scale;
      var n = it.str.length, cw = width / n;
      for (var i = 0; i < n; i++) out.push({ x: x + cw * i, w: cw, y: y, h: size, s: it.str[i] });
    });
    return out;
  }

  function normGroup(g) {
    var lat = "AKBCEMHPTXO", cyr = "АКВСЕМНРТХО";
    g = g.toUpperCase().replace(/\s+/g, "");
    var r = "";
    for (var i = 0; i < g.length; i++) { var p = lat.indexOf(g[i]); r += p >= 0 ? cyr[p] : g[i]; }
    return r;
  }

  function find(sheet, group) {
    var g = normGroup(group);
    for (var i = 0; i < sheet.columns.length; i++)
      for (var j = 0; j < sheet.columns[i].groups.length; j++)
        if (normGroup(sheet.columns[i].groups[j]) === g) return sheet.columns[i];
    return null;
  }

  return { parse: parse, parseGrid: parseGrid, addRect: addRect, addLine: addLine, bordersFromOps: bordersFromOps, groupTokens: groupTokens, TIME_G: TIME_G, parsePage: parsePage, glyphsFromContent: glyphsFromContent, find: find, normGroup: normGroup };
})();
if (typeof module !== "undefined") module.exports = Sched;
