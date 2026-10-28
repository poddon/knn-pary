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
    public List<Column> columns = new ArrayList<>();
    public Column find(String group) {
      String g = normGroup(group);
      for (Column c : columns) for (String x : c.groups) if (normGroup(x).equals(g)) return c;
      return null;
    }
  }

  public static String normGroup(String g) { return g.toUpperCase(Locale.ROOT).replace(" ", "").replace('A', 'А').replace('K', 'К').replace('B', 'В').replace('C', 'С').replace('E', 'Е').replace('M', 'М').replace('H', 'Н').replace('P', 'Р').replace('T', 'Т').replace('X', 'Х').replace('O', 'О'); }

  static class Line { float y, h; List<Glyph> g = new ArrayList<>(); String text; int[] map; }

  static final Pattern LABEL = Pattern.compile("^\\s*(\\d{1,2})\\s*\\)\\s*(\\d{1,2})[.:](\\d{2})\\s*[-–—]\\s*(\\d{1,2})[.:](\\d{2})");
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
      Lesson p = new Lesson(); p.num = Integer.parseInt(m.group(1));
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
      for (String g : sb.toString().split("[,;]+")) { g = g.replaceAll("\\s+", ""); if (!g.isEmpty() && !SPEC.matcher(g).matches()) c.groups.add(g); }
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
        List<String> teachers = new ArrayList<>();
        int cut = texts.size();
        for (int i = texts.size() - 1; i >= 0; i--) {
          String[] items = texts.get(i).split(",");
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
          teachers.addAll(0, names); cut = i;
        }
        String subject = clean(String.join(" ", texts.subList(0, cut))).replaceAll(",$", "");
        if (subject.isEmpty() && teachers.isEmpty()) continue;
        Lesson p = new Lesson(); p.num = proto.get(r).num; p.time = proto.get(r).time; p.subject = subject; p.teachers = teachers;
        sheet.columns.get(ci).lessons.add(p);
      }
    }
    return sheet;
  }
}
