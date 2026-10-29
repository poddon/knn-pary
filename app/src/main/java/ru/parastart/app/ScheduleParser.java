package ru.parastart.app;

import java.util.*;
import java.util.regex.*;

/** Parses an Excel-exported schedule page: columns = groups, rows = pairs, cell = subject + teacher surnames. Pure Java (no Android). */
public class ScheduleParser {

  public static class Glyph {
    public final float x, w, y, h; public final String s;
    public Glyph(float x, float w, float y, float h, String s) { this.x = x; this.w = w; this.y = y; this.h = h; this.s = s; }
    float cx() { return x + w / 2; }
  }

  public static class Lesson {
    public int num; public String time = "", subject = ""; public List<String> teachers = new ArrayList<>();
    public String toString() { return num + ") " + time + " | " + subject + " | " + teachers; }
  }

  public static class Column {
    public List<String> groups = new ArrayList<>(); public String spec = ""; public List<Lesson> lessons = new ArrayList<>();
  }

  public static class Sheet {
    public String date = "", weekday = "", course = "";
    public boolean shared;
    public List<Column> columns = new ArrayList<>();
    public Column find(String group) {
      String g = normGroup(group);
      for (Column c : columns) for (String x : c.groups) if (normGroup(x).equals(g)) return c;
      return null;
    }
  }

  public static String normGroup(String g) { return g.toUpperCase(Locale.ROOT).replace(" ", "").replace('A', 'А').replace('K', 'К').replace('B', 'В').replace('C', 'С').replace('E', 'Е').replace('M', 'М').replace('H', 'Н').replace('P', 'Р').replace('T', 'Т').replace('X', 'Х').replace('O', 'О'); }

  static class Line { float y, h; List<Glyph> g = new ArrayList<>(); String text; int[] map; }

  static final Pattern LABEL = Pattern.compile("^\\s*(?:(\\d{1,2})\\s*(?:\\)|-?\\s*(?:я\\s*)?пара|\\.(?!\\d))\\s*)?(\\d{1,2})[.:](\\d{2})\\s*[-–—]\\s*(\\d{1,2})[.:](\\d{2})", Pattern.CASE_INSENSITIVE);
  static final Pattern TIME = Pattern.compile("(\\d{1,2})[.:](\\d{2})\\s*[-–—]\\s*(\\d{1,2})[.:](\\d{2})");
  static final Pattern NUM = Pattern.compile("^\\s*(\\d{1,2})\\s*(?:\\)|-?\\s*(?:я\\s*)?пара|\\.(?!\\d))", Pattern.CASE_INSENSITIVE);
  static final Pattern GROUP_TOKEN = Pattern.compile("^[0-9][0-9А-ЯЁA-Zа-яё.\\-/]*$");
  static final Pattern SPEC = Pattern.compile("\\d{2}\\.\\d{2}\\.\\d{2}");
  static final Pattern DATE = Pattern.compile("(\\d{1,2}\\.\\d{1,2}\\.\\d{2,4})");
  static final Pattern COURSE = Pattern.compile("(\\d)\\s*курс", Pattern.CASE_INSENSITIVE);
  static final Pattern DAY = Pattern.compile("(понедельник|вторник|среда|четверг|пятница|суббота|воскресенье)", Pattern.CASE_INSENSITIVE);
  static final Pattern NAME = Pattern.compile("^[А-ЯЁ][а-яё]+(?:-[А-ЯЁ][а-яё]+)?(?:\\s+[А-ЯЁ]\\.\\s?(?:[А-ЯЁ]\\.?)?)?$");

  static List<Line> lines(List<Glyph> glyphs) {
    List<Glyph> gs = new ArrayList<>(glyphs);
    gs.sort((a, b) -> Float.compare(a.y, b.y));
    List<Line> out = new ArrayList<>();
    for (Glyph g : gs) {
      Line target = null;
      for (int i = out.size() - 1; i >= 0 && i >= out.size() - 4; i--) {
        Line l = out.get(i);
        if (Math.abs(g.y - l.y) > Math.max(2f, l.h * 0.3f)) continue;
        if (g.s.trim().isEmpty() || !collides(l, g)) { target = l; break; }
      }
      if (target != null) target.g.add(g);
      else { Line l = new Line(); l.y = g.y; l.h = g.h; l.g.add(g); out.add(l); }
    }
    for (Line l : out) {
      l.g.sort((a, b) -> Float.compare(a.x, b.x));
      StringBuilder sb = new StringBuilder(); List<Integer> map = new ArrayList<>();
      Glyph prev = null;
      for (int i = 0; i < l.g.size(); i++) {
        Glyph g = l.g.get(i);
        if (prev != null && g.x - (prev.x + prev.w) > Math.max(1.2f, g.h * 0.18f) && !prev.s.equals(" ") && !g.s.equals(" ")) { sb.append(' '); map.add(-1); }
        for (int k = 0; k < g.s.length(); k++) { sb.append(g.s.charAt(k)); map.add(i); }
        prev = g;
      }
      l.text = sb.toString();
      l.map = new int[map.size()]; for (int i = 0; i < map.size(); i++) l.map[i] = map.get(i);
    }
    return out;
  }

  static boolean collides(Line l, Glyph g) {
    for (Glyph o : l.g) {
      if (o.s.trim().isEmpty()) continue;
      float ov = Math.min(o.x + o.w, g.x + g.w) - Math.max(o.x, g.x);
      if (ov > Math.min(o.w, g.w) * 0.35f) return true;
    }
    return false;
  }

  /** Segments of a line separated by big gaps: returns [x0, x1, text]. */
  static List<Object[]> segments(Line l, float gap) {
    List<Object[]> out = new ArrayList<>();
    StringBuilder sb = new StringBuilder(); float x0 = 0, x1 = 0; Glyph prev = null;
    for (Glyph g : l.g) {
      if (prev == null || g.x - (prev.x + prev.w) > gap) {
        if (prev != null) out.add(new Object[]{x0, x1, sb.toString().trim()});
        sb.setLength(0); x0 = g.x;
      } else if (g.x - (prev.x + prev.w) > Math.max(1.2f, g.h * 0.18f) && !prev.s.equals(" ") && !g.s.equals(" ")) sb.append(' ');
      sb.append(g.s); x1 = g.x + g.w; prev = g;
    }
    if (prev != null) out.add(new Object[]{x0, x1, sb.toString().trim()});
    out.removeIf(o -> ((String) o[2]).isEmpty());
    return out;
  }

  static String clean(String s) { return s.replaceAll("\\s+", " ").trim(); }

  public static Sheet parsePage(List<Glyph> glyphs, Set<String> knownSurnames) {
    Sheet sheet = new Sheet();
    List<Line> ls = lines(glyphs);

    // title info
    for (Line l : ls) {
      if (sheet.date.isEmpty() && l.text.toLowerCase().contains("расписание")) {
        Matcher m = DATE.matcher(l.text); if (m.find()) sheet.date = m.group(1);
      }
      Matcher d = DAY.matcher(l.text); if (sheet.weekday.isEmpty() && d.find()) sheet.weekday = d.group(1).toLowerCase();
      Matcher c = COURSE.matcher(l.text); if (sheet.course.isEmpty() && c.find()) sheet.course = c.group(1);
    }

    // pair labels
    List<float[]> labels = new ArrayList<>(); List<Lesson> proto = new ArrayList<>();
    float labelRight = 0;
    for (Line l : ls) {
      Matcher m = LABEL.matcher(l.text);
      if (!m.find()) continue;
      int endIdx = m.end() - 1; while (endIdx > 0 && l.map[endIdx] < 0) endIdx--;
      Glyph last = l.g.get(l.map[endIdx]);
      labelRight = Math.max(labelRight, last.x + last.w);
      labels.add(new float[]{l.y});
      Lesson p = new Lesson(); p.num = m.group(1) != null ? Integer.parseInt(m.group(1)) : proto.size() + 1;
      p.time = m.group(2) + ":" + m.group(3) + "–" + m.group(4) + ":" + m.group(5);
      proto.add(p);
    }
    if (labels.isEmpty()) return sheet;

    // header line
    Line header = null;
    for (Line l : ls) if (l.y < labels.get(0)[0] && l.text.trim().startsWith("Пара")) header = l;
    Line spec = null;
    for (Line l : ls) {
      if (l.y >= labels.get(0)[0] || (header != null && l.y <= header.y)) continue;
      Matcher m = SPEC.matcher(l.text); int n = 0; while (m.find()) n++;
      if (n >= 1 && !l.text.toLowerCase().contains("расписание")) { spec = l; break; }
    }
    if (header == null) {
      for (Line l : ls) if (l.y < labels.get(0)[0] && l.text.matches(".*\\d{3,4}[А-ЯЁA-Z].*\\d{3,4}[А-ЯЁA-Z].*")) { header = l; break; }
    }
    if (header == null) return sheet;
    float left = labelRight + 1;

    // column centers
    List<Float> centers = new ArrayList<>(); List<String> specs = new ArrayList<>();
    if (spec != null) {
      for (Object[] s : segments(spec, 8)) {
        if (SPEC.matcher((String) s[2]).find() && (float) s[1] > left) { centers.add(((float) s[0] + (float) s[1]) / 2); specs.add((String) s[2]); }
      }
    }
    float headerTop = header.y - header.h * 1.4f, headerBottom = spec != null ? spec.y - spec.h * 0.6f : header.y + header.h * 1.4f;
    List<Object[]> headSegs = new ArrayList<>();
    for (Line l : ls) if (l.y >= headerTop && l.y <= headerBottom && l.h <= header.h * 1.4f && !l.text.toLowerCase().contains("курс")) for (Object[] s : segments(l, 8)) if ((float) s[1] > left && !((String) s[2]).startsWith("Пара")) headSegs.add(s);
    if (centers.isEmpty()) {
      headSegs.sort((a, b) -> Float.compare((float) a[0], (float) b[0]));
      float cx0 = -1, cx1 = -1;
      for (Object[] s : headSegs) {
        if (cx0 < 0) { cx0 = (float) s[0]; cx1 = (float) s[1]; continue; }
        if ((float) s[0] - cx1 > 24) { centers.add((cx0 + cx1) / 2); specs.add(""); cx0 = (float) s[0]; }
        cx1 = Math.max(cx1, (float) s[1]);
      }
      if (cx0 >= 0) { centers.add((cx0 + cx1) / 2); specs.add(""); }
    }
    int nc = centers.size();
    float[] lo = new float[nc], hi = new float[nc];
    for (int i = 0; i < nc; i++) {
      lo[i] = i == 0 ? left : (centers.get(i - 1) + centers.get(i)) / 2;
      hi[i] = i == nc - 1 ? Float.MAX_VALUE : (centers.get(i) + centers.get(i + 1)) / 2;
      Column c = new Column(); c.spec = specs.get(i);
      StringBuilder sb = new StringBuilder();
      for (Object[] s : headSegs) { float cx = ((float) s[0] + (float) s[1]) / 2; if (cx >= lo[i] && cx < hi[i]) sb.append(s[2]).append(","); }
      c.groups.addAll(groupTokens(sb.toString()));
      sheet.columns.add(c);
    }

    // row bands
    int nr = labels.size();
    float[] top = new float[nr], bot = new float[nr];
    float firstTop = (spec != null ? spec.y : header.y) + 2;
    for (int r = 0; r < nr; r++) {
      float y = labels.get(r)[0];
      top[r] = r == 0 ? firstTop : bot[r - 1];
      bot[r] = r == nr - 1 ? y + Math.max(20, y - top[r]) : (y + labels.get(r + 1)[0]) / 2;
    }

    // cells
    for (int ci = 0; ci < nc; ci++) {
      for (int r = 0; r < nr; r++) {
        List<Glyph> cell = new ArrayList<>();
        for (Glyph g : glyphs) { float cx = g.cx(); if (cx >= lo[ci] && cx < hi[ci] && g.y > top[r] && g.y <= bot[r] && !g.s.trim().isEmpty()) cell.add(g); }
        if (cell.isEmpty()) continue;
        List<Line> cl = lines(cell);
        List<String> texts = new ArrayList<>(); for (Line l : cl) texts.add(clean(l.text));
        String[] st = new String[1]; List<String> teachers = split(texts, knownSurnames, st);
        String subject = st[0];
        if (subject.isEmpty() && teachers.isEmpty()) continue;
        Lesson p = new Lesson(); p.num = proto.get(r).num; p.time = proto.get(r).time; p.subject = subject; p.teachers = teachers;
        sheet.columns.get(ci).lessons.add(p);
      }
    }
    return sheet;
  }

  /** Splits cell lines into subject (top) and teacher names (bottom lines). */
  static List<String> split(List<String> texts, Set<String> knownSurnames, String[] subjectOut) {
    List<String> teachers = new ArrayList<>();
    int cut = texts.size();
    for (int i = texts.size() - 1; i >= 0; i--) {
      String[] items = texts.get(i).split("[,;]");
      boolean nameLine = true, known = true; List<String> names = new ArrayList<>();
      for (String it : items) {
        it = it.trim(); if (it.isEmpty()) continue;
        if (!NAME.matcher(it).matches()) { nameLine = false; break; }
        names.add(it);
        if (!knownSurnames.contains(it.split("\\s+")[0])) known = false;
      }
      if (!nameLine || names.isEmpty()) break;
      if (i != texts.size() - 1 && !known) break;
      if (i == texts.size() - 1 && texts.size() == 1 && !known) break;
      for (int k = names.size() - 1; k >= 0; k--) if (!teachers.contains(names.get(k))) teachers.add(0, names.get(k));
      cut = i;
    }
    List<String> subj = new ArrayList<>();
    for (String t : texts.subList(0, cut)) if (!subj.contains(t)) subj.add(t);
    subjectOut[0] = clean(String.join(" ", subj)).replaceAll("[,;]$", "");
    return teachers;
  }

  static void titleInfo(Sheet sheet, List<Line> ls) {
    for (Line l : ls) {
      if (sheet.date.isEmpty() && l.text.toLowerCase().contains("расписание")) { Matcher m = DATE.matcher(l.text); if (m.find()) sheet.date = m.group(1); }
      Matcher d = DAY.matcher(l.text); if (sheet.weekday.isEmpty() && d.find()) sheet.weekday = d.group(1).toLowerCase();
      Matcher c = COURSE.matcher(l.text); if (sheet.course.isEmpty() && c.find()) sheet.course = c.group(1);
    }
    if (sheet.date.isEmpty()) for (Line l : ls) { Matcher m = DATE.matcher(l.text); if (m.find() && !SPEC.matcher(l.text).find()) { sheet.date = m.group(1); break; } }
  }

  // ===================== grid-based parsing =====================

  /** A drawn table border: horizontal (pos = y, a..b = x range) or vertical (pos = x, a..b = y range). Top-down y. */
  public static class Seg {
    public final boolean horiz; public final float pos, a, b;
    public Seg(boolean horiz, float pos, float a, float b) { this.horiz = horiz; this.pos = pos; this.a = Math.min(a, b); this.b = Math.max(a, b); }
  }

  /** Turns a drawn rectangle into border segments. Thin rectangles are lines; big filled ones are backgrounds and ignored. */
  public static void addRect(List<Seg> out, float x0, float y0, float x1, float y1, boolean stroked) {
    float l = Math.min(x0, x1), r = Math.max(x0, x1), t = Math.min(y0, y1), b = Math.max(y0, y1);
    float w = r - l, h = b - t;
    if (h <= 3f && w > 3f) out.add(new Seg(true, (t + b) / 2, l, r));
    else if (w <= 3f && h > 3f) out.add(new Seg(false, (l + r) / 2, t, b));
    else if (stroked && w > 3f && h > 3f) {
      out.add(new Seg(true, t, l, r)); out.add(new Seg(true, b, l, r));
      out.add(new Seg(false, l, t, b)); out.add(new Seg(false, r, t, b));
    }
  }

  public static void addLine(List<Seg> out, float x0, float y0, float x1, float y1) {
    if (Math.abs(y1 - y0) < 1.5f && Math.abs(x1 - x0) > 3f) out.add(new Seg(true, (y0 + y1) / 2, x0, x1));
    else if (Math.abs(x1 - x0) < 1.5f && Math.abs(y1 - y0) > 3f) out.add(new Seg(false, (x0 + x1) / 2, y0, y1));
  }

  static float[] cluster(List<Float> v, float tol) {
    Collections.sort(v);
    List<Float> out = new ArrayList<>();
    float sum = 0; int n = 0; float last = Float.NaN;
    for (float x : v) {
      if (n > 0 && x - last > tol) { out.add(sum / n); sum = 0; n = 0; }
      sum += x; n++; last = x;
    }
    if (n > 0) out.add(sum / n);
    float[] r = new float[out.size()]; for (int i = 0; i < r.length; i++) r[i] = out.get(i);
    return r;
  }

  /** How much of [a,b] is covered by segments of the given orientation lying at pos (±tol). */
  static float coverage(List<Seg> segs, boolean horiz, float pos, float a, float b, float tol) {
    List<float[]> iv = new ArrayList<>();
    for (Seg s : segs) if (s.horiz == horiz && Math.abs(s.pos - pos) <= tol) {
      float lo = Math.max(a, s.a), hi = Math.min(b, s.b);
      if (hi > lo) iv.add(new float[]{lo, hi});
    }
    iv.sort((p, q) -> Float.compare(p[0], q[0]));
    float covered = 0, curLo = -1, curHi = -1;
    for (float[] i : iv) {
      if (curHi < 0 || i[0] > curHi) { if (curHi > 0) covered += curHi - curLo; curLo = i[0]; curHi = i[1]; }
      else curHi = Math.max(curHi, i[1]);
    }
    if (curHi > 0) covered += curHi - curLo;
    return b > a ? covered / (b - a) : 0;
  }

  static class Cell { float x0, x1, y0, y1; List<Glyph> g = new ArrayList<>(); List<String> texts = new ArrayList<>(); String text = ""; int col0; }

  static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }

  /** Builds merged table cells from drawn borders. Returns null when the page has no usable grid. */
  static List<Cell> cells(List<Seg> segs, List<Glyph> glyphs) {
    List<Float> xv = new ArrayList<>(), yv = new ArrayList<>();
    for (Seg s : segs) (s.horiz ? yv : xv).add(s.pos);
    float[] xs = cluster(xv, 2.5f), ys = cluster(yv, 2.5f);
    int nx = xs.length - 1, ny = ys.length - 1;
    if (nx < 2 || ny < 2 || nx * ny > 20000) return null;
    int[] parent = new int[nx * ny];
    for (int i = 0; i < parent.length; i++) parent[i] = i;
    for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
      if (i + 1 < nx && coverage(segs, false, xs[i + 1], ys[j], ys[j + 1], 2.5f) < 0.5f) parent[find(parent, j * nx + i)] = find(parent, j * nx + i + 1);
      if (j + 1 < ny && coverage(segs, true, ys[j + 1], xs[i], xs[i + 1], 2.5f) < 0.5f) parent[find(parent, j * nx + i)] = find(parent, (j + 1) * nx + i);
    }
    Map<Integer, Cell> map = new LinkedHashMap<>();
    for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) {
      int root = find(parent, j * nx + i);
      Cell c = map.get(root);
      if (c == null) { c = new Cell(); c.x0 = xs[i]; c.x1 = xs[i + 1]; c.y0 = ys[j]; c.y1 = ys[j + 1]; c.col0 = i; map.put(root, c); }
      else { c.x0 = Math.min(c.x0, xs[i]); c.x1 = Math.max(c.x1, xs[i + 1]); c.y0 = Math.min(c.y0, ys[j]); c.y1 = Math.max(c.y1, ys[j + 1]); c.col0 = Math.min(c.col0, i); }
    }
    List<Cell> out = new ArrayList<>(map.values());
    for (Glyph g : glyphs) {
      if (g.s.trim().isEmpty()) continue;
      float cx = g.cx(), cy = g.y - g.h * 0.3f;
      for (Cell c : out) if (cx >= c.x0 && cx < c.x1 && cy >= c.y0 && cy < c.y1) { c.g.add(g); break; }
    }
    for (Cell c : out) {
      for (Line l : lines(c.g)) { String t = clean(l.text); if (!t.isEmpty()) c.texts.add(t); }
      c.text = String.join(" ", c.texts);
    }
    return out;
  }

  static List<String> groupTokens(String text) {
    List<String> out = new ArrayList<>();
    for (String t : text.split("[,;\\s]+")) {
      t = t.trim();
      if (t.length() < 3 || SPEC.matcher(t).matches() || DATE.matcher(t).matches() || TIME.matcher(t).find()) continue;
      if (GROUP_TOKEN.matcher(t).matches() && t.matches(".*\\d{3,}.*|.*\\d.*[А-ЯЁA-Zа-яё].*")) out.add(t);
    }
    return out;
  }

  /** Parses a page using its drawn table grid. Returns null when there is no grid or no pairs in it. */
  public static Sheet parseGrid(List<Glyph> glyphs, List<Seg> segs, Set<String> known) {
    if (segs == null || segs.size() < 6) return null;
    List<Cell> cells = cells(segs, glyphs);
    if (cells == null) return null;
    Sheet sheet = new Sheet();
    titleInfo(sheet, lines(glyphs));

    // pair labels: cells in the first table column holding a time range
    int minCol = Integer.MAX_VALUE;
    for (Cell c : cells) if (TIME.matcher(c.text).find()) minCol = Math.min(minCol, c.col0);
    List<Cell> labels = new ArrayList<>();
    for (Cell c : cells) if (c.col0 == minCol && TIME.matcher(c.text).find()) labels.add(c);
    if (labels.isEmpty()) return null;
    labels.sort((a, b) -> Float.compare(a.y0, b.y0));
    float labelRight = 0; for (Cell c : labels) labelRight = Math.max(labelRight, c.x1);

    // header candidates: cells right of the label column, above some label, with group-like tokens
    List<Cell> heads = new ArrayList<>();
    for (Cell c : cells) {
      if (c.x0 < labelRight - 1 || c.y1 > labels.get(labels.size() - 1).y0 + 1) continue;
      boolean inBlock = false; for (Cell l : labels) if (c.y0 >= l.y0 - 1 && c.y1 <= l.y1 + 1) inBlock = true;
      if (inBlock) continue;
      if (!groupTokens(c.text).isEmpty()) heads.add(c);
    }
    if (heads.isEmpty()) return null;

    Map<String, Column> byKey = new LinkedHashMap<>();
    int n = 0;
    for (Cell lab : labels) {
      n++;
      Matcher tm = TIME.matcher(lab.text); tm.find();
      Matcher nm = NUM.matcher(lab.text);
      int num = nm.find() ? Integer.parseInt(nm.group(1)) : n;
      String time = tm.group(1) + ":" + tm.group(2) + "–" + tm.group(3) + ":" + tm.group(4);
      // nearest header band above this label
      Cell ref = null;
      for (Cell h : heads) if (h.y1 <= lab.y0 + 1 && (ref == null || h.y1 > ref.y1)) ref = h;
      if (ref == null) continue;
      List<Cell> band = new ArrayList<>();
      for (Cell h : heads) if (h.y1 <= lab.y0 + 1 && h.y0 < ref.y1 - 1 && h.y1 > ref.y0 + 1) band.add(h);
      band.sort((a, b) -> Float.compare(a.x0, b.x0));
      for (Cell h : band) {
        String key = (int) h.x0 + ":" + (int) h.y0;
        Column col = byKey.get(key);
        if (col == null) {
          col = new Column(); col.groups.addAll(groupTokens(h.text));
          for (Cell s2 : cells) if (s2.y0 >= h.y1 - 1 && s2.y1 <= lab.y0 + 1 && s2.x0 >= h.x0 - 1 && s2.x1 <= h.x1 + 1) {
            Matcher sm = SPEC.matcher(s2.text); if (sm.find()) { col.spec = sm.group(); break; }
          }
          byKey.put(key, col);
        }
        // cells of this pair block that overlap the group column
        List<Cell> parts = new ArrayList<>();
        for (Cell c : cells) {
          if (c.y1 <= lab.y0 + 1 || c.y0 >= lab.y1 - 1) continue;
          float ov = Math.min(c.x1, h.x1) - Math.max(c.x0, h.x0);
          if (ov < Math.min(c.x1 - c.x0, h.x1 - h.x0) * 0.5f) continue;
          if (c.texts.isEmpty()) continue;
          parts.add(c);
        }
        parts.sort((a, b) -> Math.abs(a.y0 - b.y0) > 1 ? Float.compare(a.y0, b.y0) : Float.compare(a.x0, b.x0));
        List<String> texts = new ArrayList<>(); for (Cell c : parts) texts.addAll(c.texts);
        if (texts.isEmpty()) continue;
        String[] st = new String[1];
        List<String> teachers = split(texts, known, st);
        if (st[0].isEmpty() && teachers.isEmpty()) continue;
        Lesson p = new Lesson(); p.num = num; p.time = time; p.subject = st[0]; p.teachers = teachers;
        col.lessons.add(p);
      }
    }
    for (Column c : byKey.values()) if (!c.groups.isEmpty()) sheet.columns.add(c);
    int lessons = 0; for (Column c : sheet.columns) lessons += c.lessons.size();
    return lessons > 0 ? sheet : null;
  }

  /** Grid first, then the text-layout fallback. */
  public static Sheet parse(List<Glyph> glyphs, List<Seg> segs, Set<String> known) {
    Sheet g = null;
    try { g = parseGrid(glyphs, segs, known); } catch (RuntimeException ignored) {}
    if (g != null) return g;
    return parsePage(glyphs, known);
  }
}
