package ru.parastart.app;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;
import org.json.*;

public class MainActivity extends Activity {
  static final String PREFS = "parastart", KEY = "links", FAV = "favs", SHEETS = "sheets", GROUP = "group", FIO = "fio", REMIND = "remind", AUTO = "autojoin";

  static final int PRIMARY = 0xFF1E4FD8, PRIMARY_2 = 0xFF4F7DF5, BG = 0xFFF2F4F9, CARD = 0xFFFFFFFF,
      TEXT = 0xFF171C2B, MUTED = 0xFF6B7385, LINE = 0xFFE3E7EF, STAR = 0xFFFFB020, STAR_OFF = 0xFFC9CED8,
      SOFT = 0xFFE8EEFD, DANGER = 0xFFD93C3C, GREEN = 0xFF0E9F6E, GREEN_SOFT = 0xFFE3F6EE;
  static final int[] AVATARS = {0xFF1E4FD8, 0xFF0E9F6E, 0xFF7C3AED, 0xFFE8590C, 0xFFD6336C, 0xFF0B7285, 0xFF5C7CFA, 0xFF2F9E44};

  final ArrayList<String> links = new ArrayList<>();
  final ArrayList<String[]> teachers = new ArrayList<>();
  final HashMap<String, String> nameByUrl = new HashMap<>();
  final HashSet<String> favs = new HashSet<>();
  final HashMap<String, ArrayList<String[]>> bySurname = new HashMap<>();
  final ArrayList<ScheduleParser.Sheet> sheets = new ArrayList<>();

  int tab = 0;
  TextView tabSchedule, tabTeachers, tabLinks, info;
  LinearLayout listBox, actionsBox;
  FrameLayout searchBox;
  EditText search;

  @Override public void onCreate(Bundle s) {
    super.onCreate(s);
    PDFBoxResourceLoader.init(getApplicationContext());
    Window w = getWindow();
    w.setStatusBarColor(PRIMARY);
    w.setNavigationBarColor(BG);
    w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    loadTeachers();
    loadFavs();
    loadLinks();
    loadSheets();
    screen();
    receive(getIntent());
  }

  @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); receive(intent); }

  @Override protected void onResume() { super.onResume(); loadLinks(); render(); Reminders.schedule(this); }

  // ---------- data ----------
  void loadTeachers() {
    try (BufferedReader r = new BufferedReader(new InputStreamReader(getAssets().open("teachers.tsv"), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        String[] p = line.split("\t");
        if (p.length == 2) { teachers.add(p); bySurname.computeIfAbsent(norm(p[0].split(" ")[0]), k -> new ArrayList<>()).add(p); String prev = nameByUrl.get(p[1]); nameByUrl.put(p[1], prev == null ? p[0] : prev + " / " + p[0]); }
      }
    } catch (IOException ignored) {}
  }

  void loadFavs() { favs.clear(); favs.addAll(getSharedPreferences(PREFS, 0).getStringSet(FAV, new HashSet<>())); }
  void saveFavs() { getSharedPreferences(PREFS, 0).edit().putStringSet(FAV, new HashSet<>(favs)).apply(); }

  void loadLinks() {
    links.clear();
    String v = getSharedPreferences(PREFS, 0).getString(KEY, "");
    for (String line : v.split("\n")) if (!line.isEmpty() && !links.contains(line)) links.add(line);
  }
  void saveLinks() { getSharedPreferences(PREFS, 0).edit().putString(KEY, TextUtils.join("\n", links)).apply(); }

  void add(String v) {
    v = v.trim();
    if (v.startsWith("https://") && !links.contains(v)) { links.add(0, v); saveLinks(); }
  }

  // ---------- layout ----------
  int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

  GradientDrawable round(int color, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp(r)); return g; }

  Drawable ripple(Drawable content) { return new RippleDrawable(ColorStateList.valueOf(0x1F1E4FD8), content, null); }

  TextView text(String s, float size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(s); t.setTextSize(size); t.setTextColor(color);
    t.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    return t;
  }

  LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

  void screen() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);

    // Header
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PRIMARY, PRIMARY_2});
    hg.setCornerRadii(new float[]{0, 0, 0, 0, dp(28), dp(28), dp(28), dp(28)});
    header.setBackground(hg);
    header.setPadding(dp(20), dp(18), dp(20), dp(18));
    header.setElevation(dp(4));

    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = text("КНН пары", 28, Color.WHITE, true);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
    LinearLayout.LayoutParams tl = lp(0, -2); tl.weight = 1;
    top.addView(title, tl);
    groupChip = text("", 14, Color.WHITE, true);
    groupChip.setGravity(Gravity.CENTER);
    groupChip.setPadding(dp(14), dp(8), dp(14), dp(8));
    GradientDrawable pc = round(0x33FFFFFF, 20); pc.setStroke(dp(1.5f), 0x80FFFFFF);
    groupChip.setBackground(pc);
    groupChip.setOnClickListener(v -> groupDialog());
    top.addView(groupChip);
    header.addView(top);
    sub = text("Ссылки на пары в один тап", 14, 0xD9FFFFFF, false);
    sub.setPadding(0, dp(2), 0, dp(16));
    sub.setOnClickListener(v -> nameDialog(null));
    header.addView(sub);

    LinearLayout seg = new LinearLayout(this);
    seg.setBackground(round(0x33FFFFFF, 14));
    seg.setPadding(dp(4), dp(4), dp(4), dp(4));
    tabSchedule = segTab("Расписание", 0);
    tabTeachers = segTab("Преподаватели", 1);
    tabLinks = segTab("Ссылки", 2);
    seg.addView(tabSchedule);
    seg.addView(tabTeachers);
    seg.addView(tabLinks);
    header.addView(seg);
    root.addView(header);

    // Body
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    body.setPadding(dp(16), dp(16), dp(16), dp(24));
    scroll.addView(body);
    root.addView(scroll, lp(-1, 0));
    ((LinearLayout.LayoutParams) scroll.getLayoutParams()).weight = 1;

    // Search
    searchBox = new FrameLayout(this);
    search = new EditText(this);
    search.setHint("Поиск преподавателя");
    search.setHintTextColor(MUTED);
    search.setTextColor(TEXT);
    search.setTextSize(16);
    search.setSingleLine(true);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    GradientDrawable sb = round(CARD, 14); sb.setStroke(dp(1), LINE);
    search.setBackground(sb);
    search.setPadding(dp(44), dp(14), dp(16), dp(14));
    searchBox.addView(search, new FrameLayout.LayoutParams(-1, -2));
    TextView glass = text("⌕", 22, MUTED, false);
    FrameLayout.LayoutParams gl = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_VERTICAL | Gravity.START);
    gl.leftMargin = dp(14);
    searchBox.addView(glass, gl);
    search.addTextChangedListener(new TextWatcher() {
      public void beforeTextChanged(CharSequence a, int b, int c, int d) {}
      public void onTextChanged(CharSequence a, int b, int c, int d) {}
      public void afterTextChanged(Editable e) { renderList(); }
    });
    LinearLayout.LayoutParams sl = lp(-1, -2); sl.bottomMargin = dp(8);
    body.addView(searchBox, sl);

    // Actions (links tab)
    actionsBox = new LinearLayout(this);
    actionsBox.setOrientation(LinearLayout.VERTICAL);
    info = text("Ссылки из PDF и уведомлений MAX собираются здесь. Можно добавить ссылку и вручную.", 14, 0xFF2B3A66, false);
    info.setBackground(round(SOFT, 14));
    info.setPadding(dp(14), dp(12), dp(14), dp(12));
    info.setLineSpacing(0, 1.15f);
    actionsBox.addView(info, lp(-1, -2));
    actionsBox.addView(pill("Загрузить PDF", true, this::pickPdf));
    LinearLayout row = new LinearLayout(this);
    TextView a = pill("Добавить ссылку", false, this::addDialog);
    TextView b = pill("Уведомления MAX", false, () -> startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
    LinearLayout.LayoutParams l1 = lp(0, -2); l1.weight = 1; l1.rightMargin = dp(5);
    LinearLayout.LayoutParams l2 = lp(0, -2); l2.weight = 1; l2.leftMargin = dp(5);
    row.addView(a, l1); row.addView(b, l2);
    actionsBox.addView(row);
    body.addView(actionsBox, lp(-1, -2));

    listBox = new LinearLayout(this);
    listBox.setOrientation(LinearLayout.VERTICAL);
    body.addView(listBox, lp(-1, -2));

    setContentView(root);
    showGroup();
    render();
    Reminders.channel(this);
    Reminders.schedule(this);
    if (fio().isEmpty()) root.post(() -> nameDialog(null));
  }

  TextView segTab(String label, int index) {
    TextView t = text(label, 14, Color.WHITE, true);
    t.setGravity(Gravity.CENTER);
    t.setSingleLine(true);
    t.setPadding(0, dp(10), 0, dp(10));
    LinearLayout.LayoutParams p = lp(0, -2); p.weight = 1;
    t.setLayoutParams(p);
    t.setOnClickListener(v -> selectTab(index));
    return t;
  }

  TextView pill(String label, boolean primary, Runnable call) {
    TextView t = text(label, 15, primary ? Color.WHITE : PRIMARY, true);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(12), dp(14), dp(12), dp(14));
    GradientDrawable g = round(primary ? PRIMARY : CARD, 14);
    if (!primary) g.setStroke(dp(1.5f), 0x551E4FD8);
    t.setBackground(ripple(g));
    if (primary) t.setElevation(dp(2));
    LinearLayout.LayoutParams p = lp(-1, -2); p.topMargin = dp(10);
    t.setLayoutParams(p);
    t.setOnClickListener(v -> call.run());
    return t;
  }

  void selectTab(int i) {
    tab = i;
    if (i != 1 && search != null) {
      InputMethodManager im = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
      im.hideSoftInputFromWindow(search.getWindowToken(), 0);
    }
    render();
  }

  void render() {
    if (listBox == null) return;
    styleTab(tabSchedule, tab == 0);
    styleTab(tabTeachers, tab == 1);
    styleTab(tabLinks, tab == 2);
    searchBox.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
    actionsBox.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
    renderList();
  }

  void styleTab(TextView t, boolean on) {
    t.setBackground(on ? round(Color.WHITE, 11) : null);
    t.setTextColor(on ? PRIMARY : 0xE6FFFFFF);
  }

  void section(String s) {
    TextView t = text(s.toUpperCase(new Locale("ru")), 12, MUTED, true);
    t.setLetterSpacing(0.06f);
    t.setPadding(dp(4), dp(18), 0, dp(8));
    listBox.addView(t);
  }

  void renderList() {
    if (listBox == null) return;
    listBox.removeAllViews();
    if (tab == 0) renderSchedule(); else if (tab == 1) renderTeachers(); else renderLinks();
  }

  static String norm(String s) { return s.toLowerCase(new Locale("ru")).replace('ё', 'е'); }

  void renderTeachers() {
    String[] q = norm(search.getText().toString()).trim().split("\\s+");
    ArrayList<String[]> fav = new ArrayList<>(), rest = new ArrayList<>();
    for (String[] t : teachers) {
      String n = norm(t[0]);
      boolean ok = true;
      for (String part : q) if (!part.isEmpty() && !n.contains(part)) { ok = false; break; }
      if (!ok) continue;
      (favs.contains(t[1]) ? fav : rest).add(t);
    }
    if (fav.isEmpty() && rest.isEmpty()) { empty("Никого не нашли", "Попробуй ввести только фамилию"); return; }
    if (!fav.isEmpty()) { section("Избранные"); for (String[] t : fav) teacherCard(t); }
    section((fav.isEmpty() ? "Преподаватели · " : "Остальные · ") + rest.size());
    for (String[] t : rest) teacherCard(t);
  }

  void teacherCard(String[] t) {
    String name = t[0], url = t[1];
    LinearLayout card = cardBase();
    card.addView(avatar(initials(name), AVATARS[Math.abs(name.hashCode()) % AVATARS.length]));

    LinearLayout mid = new LinearLayout(this);
    mid.setOrientation(LinearLayout.VERTICAL);
    String[] w = name.split(" ", 2);
    mid.addView(text(w[0], 16, TEXT, true));
    if (w.length > 1) mid.addView(text(w[1], 14, MUTED, false));
    LinearLayout.LayoutParams ml = lp(0, -2); ml.weight = 1; ml.leftMargin = dp(14);
    card.addView(mid, ml);

    boolean on = favs.contains(url);
    TextView star = text(on ? "★" : "☆", 24, on ? STAR : STAR_OFF, false);
    star.setPadding(dp(10), dp(4), dp(4), dp(4));
    star.setOnClickListener(v -> { if (!favs.remove(url)) favs.add(url); saveFavs(); renderList(); });
    card.addView(star);

    TextView go = text("›", 26, PRIMARY, false);
    go.setPadding(dp(6), 0, 0, dp(2));
    card.addView(go);

    card.setOnClickListener(v -> open(url, name));
    card.setOnLongClickListener(v -> { copy(url); return true; });
    listBox.addView(card);
  }

  void renderLinks() {
    section("Найденные конференции · " + links.size());
    if (links.isEmpty()) { empty("Пока пусто", "Загрузи PDF с расписанием или добавь ссылку вручную"); return; }
    for (String link : links) {
      String teacher = nameByUrl.get(link);
      LinearLayout card = cardBase();
      card.addView(teacher != null ? avatar(initials(teacher), AVATARS[Math.abs(teacher.hashCode()) % AVATARS.length]) : avatar("▶", 0xFF0E9F6E));
      LinearLayout mid = new LinearLayout(this);
      mid.setOrientation(LinearLayout.VERTICAL);
      mid.addView(text(teacher != null ? teacher : "Конференция", 16, TEXT, true));
      TextView u = text(link.replaceFirst("^https?://", ""), 13, MUTED, false);
      u.setSingleLine(true);
      u.setEllipsize(TextUtils.TruncateAt.MIDDLE);
      mid.addView(u);
      LinearLayout.LayoutParams ml = lp(0, -2); ml.weight = 1; ml.leftMargin = dp(14);
      card.addView(mid, ml);
      TextView del = text("✕", 18, STAR_OFF, false);
      del.setPadding(dp(12), dp(6), dp(4), dp(6));
      del.setOnClickListener(v -> new AlertDialog.Builder(this).setMessage("Удалить ссылку из списка?")
          .setPositiveButton("Удалить", (d, x) -> { links.remove(link); saveLinks(); renderList(); })
          .setNegativeButton("Отмена", null).show());
      card.addView(del);
      card.setOnClickListener(v -> open(link, teacher != null ? teacher : "Конференция"));
      card.setOnLongClickListener(v -> { copy(link); return true; });
      listBox.addView(card);
    }
  }

  LinearLayout cardBase() {
    LinearLayout card = new LinearLayout(this);
    card.setGravity(Gravity.CENTER_VERTICAL);
    card.setPadding(dp(14), dp(12), dp(12), dp(12));
    card.setBackground(ripple(round(CARD, 18)));
    card.setElevation(dp(1.5f));
    LinearLayout.LayoutParams p = lp(-1, -2);
    p.bottomMargin = dp(10);
    p.leftMargin = dp(1); p.rightMargin = dp(1);
    card.setLayoutParams(p);
    return card;
  }

  TextView avatar(String s, int color) {
    TextView a = text(s, 16, Color.WHITE, true);
    a.setGravity(Gravity.CENTER);
    GradientDrawable g = new GradientDrawable();
    g.setShape(GradientDrawable.OVAL);
    g.setColor(color);
    a.setBackground(g);
    a.setLayoutParams(lp(dp(46), dp(46)));
    return a;
  }

  void empty(String head, String sub) {
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setGravity(Gravity.CENTER_HORIZONTAL);
    box.setPadding(dp(16), dp(36), dp(16), dp(36));
    TextView h = text(head, 17, TEXT, true); h.setGravity(Gravity.CENTER);
    TextView s = text(sub, 14, MUTED, false); s.setGravity(Gravity.CENTER); s.setPadding(0, dp(6), 0, 0);
    box.addView(h); box.addView(s);
    listBox.addView(box);
  }

  static String initials(String name) {
    String[] w = name.trim().split("\\s+");
    String r = w[0].substring(0, 1);
    if (w.length > 1) r += w[1].substring(0, 1);
    return r.toUpperCase(new Locale("ru"));
  }

  // ---------- actions ----------
  void open(String url) { open(url, ""); }

  void open(String url, String title) {
    if (fio().isEmpty()) { nameDialog(() -> JoinActivity.open(this, url, title)); return; }
    JoinActivity.open(this, url, title);
  }

  void copy(String url) {
    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
    cm.setPrimaryClip(ClipData.newPlainText("ссылка", url));
    Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show();
  }

  void addDialog() {
    EditText edit = new EditText(this);
    edit.setHint("https://vks.knn-nk.ru/rooms/…");
    edit.setSingleLine(true);
    FrameLayout wrap = new FrameLayout(this);
    wrap.setPadding(dp(20), dp(8), dp(20), 0);
    wrap.addView(edit);
    new AlertDialog.Builder(this).setTitle("Ссылка на пару").setView(wrap)
        .setPositiveButton("Сохранить", (d, w) -> {
          String v = edit.getText().toString().trim();
          if (!v.startsWith("https://")) Toast.makeText(this, "Ссылка должна начинаться с https://", Toast.LENGTH_SHORT).show();
          else { add(v); renderList(); }
        })
        .setNegativeButton("Отмена", null).show();
  }

  boolean receive(Intent intent) {
    if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return false;
    Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
    if (uri == null) return false;
    parse(uri);
    return true;
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == 100 && res == RESULT_OK && data != null) parse(data.getData());
  }

  void pickPdf() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("application/pdf");
    startActivityForResult(i, 100);
  }

  static class GlyphStripper extends PDFTextStripper {
    final ArrayList<ScheduleParser.Glyph> glyphs = new ArrayList<>();
    GlyphStripper() throws IOException { super(); }
    @Override protected void writeString(String text, List<TextPosition> positions) throws IOException {
      for (TextPosition p : positions) {
        String u = p.getUnicode();
        if (u == null || u.isEmpty()) continue;
        glyphs.add(new ScheduleParser.Glyph(p.getXDirAdj(), p.getWidthDirAdj(), p.getYDirAdj(), Math.max(p.getFontSizeInPt(), p.getHeightDir()), u));
      }
      super.writeString(text, positions);
    }
  }

  void parse(Uri uri) {
    loading = true;
    selectTab(0);
    if (info != null) info.setText("Читаю PDF…");
    new Thread(() -> {
      try (InputStream input = getContentResolver().openInputStream(uri); PDDocument doc = PDDocument.load(input)) {
        HashSet<String> known = new HashSet<>();
        for (String[] t : teachers) known.add(t[0].split(" ")[0]);
        ArrayList<ScheduleParser.Sheet> found = new ArrayList<>();
        StringBuilder all = new StringBuilder();
        int textChars = 0;
        GlyphStripper st = new GlyphStripper();
        for (int pg = 1; pg <= doc.getNumberOfPages(); pg++) {
          st.glyphs.clear();
          st.setStartPage(pg); st.setEndPage(pg);
          all.append(st.getText(doc)).append('\n');
          textChars += st.glyphs.size();
          ScheduleParser.Sheet sh = ScheduleParser.parse(st.glyphs, Borders.of(doc.getPage(pg - 1)), known);
          if (sh.date.isEmpty()) sh.date = new java.text.SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(new Date());
          int lessons = 0; for (ScheduleParser.Column c : sh.columns) lessons += c.lessons.size();
          if (lessons > 0) found.add(sh);
        }
        ArrayList<String> urls = extract(all.toString());
        String why = found.isEmpty() ? diagnose(all.toString(), textChars, doc.getNumberOfPages()) : null;
        runOnUiThread(() -> {
          loading = false;
          for (int i = urls.size() - 1; i >= 0; i--) add(urls.get(i));
          for (ScheduleParser.Sheet sh : found) storeSheet(sh);
          lastLoaded.clear(); lastLoaded.addAll(found);
          if (!found.isEmpty()) {
            problem = null;
            saveSheets();
            Reminders.schedule(this);
            info.setText("Расписание загружено.");
            selectedDate = found.get(0).date;
            for (ScheduleParser.Sheet sh : found) if (!group().isEmpty() && sh.find(group()) != null) { selectedDate = sh.date; break; }
            selectTab(0);
            boolean has = false; for (ScheduleParser.Sheet sh : found) if (sh.find(group()) != null) has = true;
            if (group().isEmpty()) groupDialog();
            else if (!has) Toast.makeText(this, "Твоей группы " + group() + " нет в этом PDF", Toast.LENGTH_LONG).show();
            else Toast.makeText(this, "Расписание на " + selectedDate + " загружено", Toast.LENGTH_SHORT).show();
          } else {
            problem = why + (urls.isEmpty() ? "" : "\n\nСсылок в файле: " + urls.size() + ", они добавлены на вкладку «Ссылки».");
            info.setText("В этом PDF не нашлось расписания по группам.");
            selectTab(0);
          }
        });
      } catch (Exception e) {
        runOnUiThread(() -> { loading = false; info.setText("Не удалось прочитать PDF: " + e.getMessage()); Toast.makeText(this, "Не удалось прочитать PDF", Toast.LENGTH_LONG).show(); renderList(); });
      }
    }).start();
  }

  static ArrayList<String> extract(String text) {
    ArrayList<String> result = new ArrayList<>();
    Matcher m = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE).matcher(text);
    while (m.find()) {
      String u = m.group().replaceAll("[.,;)]+$", "");
      if (!result.contains(u)) result.add(u);
    }
    return result;
  }

  // ---------- group & schedule ----------
  TextView sub, groupChip;
  boolean loading = false;
  String selectedDate = null, problem = null;
  final ArrayList<ScheduleParser.Sheet> lastLoaded = new ArrayList<>();

  String group() { return getSharedPreferences(PREFS, 0).getString(GROUP, ""); }

  void setGroup(String g) {
    getSharedPreferences(PREFS, 0).edit().putString(GROUP, g).apply();
    showGroup();
    Reminders.schedule(this);
    selectTab(0);
  }

  String fio() { return getSharedPreferences(PREFS, 0).getString(FIO, ""); }

  void showGroup() {
    String g = group(), f = fio();
    groupChip.setText(g.isEmpty() ? "Группа ▾" : g + " ▾");
    String who = f.isEmpty() ? "Нажми, чтобы ввести ФИО ✎" : f + " ✎";
    sub.setText(who);
  }

  void nameDialog(Runnable after) {
    EditText edit = new EditText(this);
    edit.setHint("Фамилия Имя Отчество");
    edit.setSingleLine(true);
    edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
    edit.setText(fio());
    FrameLayout wrap = new FrameLayout(this);
    wrap.setPadding(dp(20), dp(8), dp(20), 0);
    wrap.addView(edit);
    new AlertDialog.Builder(this).setTitle("Твоё ФИО")
        .setMessage("С этим именем ты будешь автоматически входить на пары")
        .setView(wrap)
        .setPositiveButton("Сохранить", (d, w) -> {
          String f = TextUtils.join(" ", edit.getText().toString().trim().split("\\s+"));
          getSharedPreferences(PREFS, 0).edit().putString(FIO, f).apply();
          showGroup();
          if (after != null && !f.isEmpty()) after.run();
        })
        .setNegativeButton("Позже", null).show();
  }

  ArrayList<String> knownGroups() {
    TreeSet<String> set = new TreeSet<>();
    for (ScheduleParser.Sheet sh : lastLoaded) for (ScheduleParser.Column c : sh.columns) set.addAll(c.groups);
    if (set.isEmpty() && !sheets.isEmpty()) {
      String d = sheets.get(0).date;
      for (ScheduleParser.Sheet sh : sheets) if (sh.date.equals(d)) for (ScheduleParser.Column c : sh.columns) set.addAll(c.groups);
    }
    return new ArrayList<>(set);
  }

  void groupDialog() {
    ArrayList<String> groups = knownGroups();
    if (groups.isEmpty()) { manualGroupDialog(); return; }
    ArrayList<String> items = new ArrayList<>(groups);
    items.add("Ввести вручную…");
    new AlertDialog.Builder(this).setTitle("Выбери свою группу")
        .setItems(items.toArray(new String[0]), (d, which) -> {
          if (which == items.size() - 1) manualGroupDialog(); else setGroup(items.get(which));
        })
        .setNegativeButton("Отмена", null).show();
  }

  void manualGroupDialog() {
    EditText edit = new EditText(this);
    edit.setHint("например, 1515А");
    edit.setSingleLine(true);
    edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
    edit.setText(group());
    FrameLayout wrap = new FrameLayout(this);
    wrap.setPadding(dp(20), dp(8), dp(20), 0);
    wrap.addView(edit);
    new AlertDialog.Builder(this).setTitle("Моя группа").setMessage("Номер группы так, как он написан в расписании").setView(wrap)
        .setPositiveButton("Сохранить", (d, w) -> {
          String g = edit.getText().toString().replace(" ", "").toUpperCase(new Locale("ru"));
          if (!g.isEmpty()) setGroup(g);
        })
        .setNegativeButton("Отмена", null).show();
  }

  static long dayKey(String date) {
    String[] p = date.split("\\.");
    if (p.length != 3) return 0;
    try {
      int y = Integer.parseInt(p[2]); if (y < 100) y += 2000;
      return y * 10000L + Integer.parseInt(p[1]) * 100L + Integer.parseInt(p[0]);
    } catch (NumberFormatException e) { return 0; }
  }

  static long todayKey(int plusDays) {
    Calendar c = Calendar.getInstance(); c.add(Calendar.DAY_OF_MONTH, plusDays);
    return c.get(Calendar.YEAR) * 10000L + (c.get(Calendar.MONTH) + 1) * 100L + c.get(Calendar.DAY_OF_MONTH);
  }

  void storeSheet(ScheduleParser.Sheet sh) {
    for (int i = sheets.size() - 1; i >= 0; i--) {
      ScheduleParser.Sheet o = sheets.get(i);
      if (o.date.equals(sh.date) && o.course.equals(sh.course)) sheets.remove(i);
    }
    sheets.add(sh);
    sheets.sort((a, b) -> Long.compare(dayKey(b.date), dayKey(a.date)));
    while (sheets.size() > 12) sheets.remove(sheets.size() - 1);
  }

  static String diagnose(String text, int chars, int pages) {
    if (chars < 20) return "В этом PDF нет текста: похоже, это картинка или скан. Такой файл приложение прочитать не может. Попроси расписание в виде обычного PDF из Excel.";
    int times = 0; Matcher m = ScheduleParser.TIME.matcher(text); while (m.find()) times++;
    List<String> groups = ScheduleParser.groupTokens(text.replace('\n', ' '));
    StringBuilder b = new StringBuilder("Не получилось найти таблицу пар в этом PDF.\n");
    b.append("Страниц: ").append(pages).append(". Время пар (вида 08.00-09.30): ").append(times > 0 ? "найдено " + times : "не найдено").append(".");
    if (!groups.isEmpty()) {
      LinkedHashSet<String> uniq = new LinkedHashSet<>(groups);
      ArrayList<String> few = new ArrayList<>(uniq);
      b.append("\nПохоже на номера групп: ").append(TextUtils.join(", ", few.subList(0, Math.min(8, few.size()))));
    }
    if (times == 0) b.append("\nВ таблице должно быть время пар, иначе приложение не понимает, где какая пара.");
    return b.toString();
  }

  ArrayList<ScheduleParser.Sheet> daysForGroup() {
    ArrayList<ScheduleParser.Sheet> r = new ArrayList<>();
    for (ScheduleParser.Sheet sh : sheets) if (sh.find(group()) != null) r.add(sh);
    r.sort((a, b) -> Long.compare(dayKey(a.date), dayKey(b.date)));
    return r;
  }

  ScheduleParser.Sheet findSheet() {
    String g = group();
    if (g.isEmpty()) return null;
    if (selectedDate != null) for (ScheduleParser.Sheet sh : sheets) if (sh.date.equals(selectedDate) && sh.find(g) != null) return sh;
    long today = todayKey(0);
    ScheduleParser.Sheet best = null;
    for (ScheduleParser.Sheet sh : sheets) {
      if (sh.find(g) == null) continue;
      long k = dayKey(sh.date);
      if (best == null) { best = sh; continue; }
      long bk = dayKey(best.date);
      // prefer today, then the nearest upcoming day, then the latest past day
      if (k == today) return sh;
      if (k > today && (bk < today || k < bk)) best = sh;
    }
    return best;
  }

  void saveSheets() {
    try {
      JSONArray arr = new JSONArray();
      for (ScheduleParser.Sheet sh : sheets) {
        JSONObject o = new JSONObject();
        o.put("date", sh.date); o.put("weekday", sh.weekday); o.put("course", sh.course);
        JSONArray cols = new JSONArray();
        for (ScheduleParser.Column c : sh.columns) {
          JSONObject co = new JSONObject();
          co.put("groups", new JSONArray(c.groups)); co.put("spec", c.spec);
          JSONArray ls = new JSONArray();
          for (ScheduleParser.Lesson l : c.lessons) {
            JSONObject lo = new JSONObject();
            lo.put("num", l.num); lo.put("time", l.time); lo.put("subject", l.subject); lo.put("teachers", new JSONArray(l.teachers));
            ls.put(lo);
          }
          co.put("lessons", ls);
          cols.put(co);
        }
        o.put("columns", cols);
        arr.put(o);
      }
      getSharedPreferences(PREFS, 0).edit().putString(SHEETS, arr.toString()).apply();
    } catch (JSONException ignored) {}
  }

  static ArrayList<String> strings(JSONArray a) {
    ArrayList<String> r = new ArrayList<>();
    if (a != null) for (int i = 0; i < a.length(); i++) r.add(a.optString(i));
    return r;
  }

  void loadSheets() { sheets.clear(); sheets.addAll(readSheets(this)); }

  static ArrayList<ScheduleParser.Sheet> readSheets(Context ctx) {
    ArrayList<ScheduleParser.Sheet> sheets = new ArrayList<>();
    try {
      JSONArray arr = new JSONArray(ctx.getSharedPreferences(PREFS, 0).getString(SHEETS, "[]"));
      for (int i = 0; i < arr.length(); i++) {
        JSONObject o = arr.getJSONObject(i);
        ScheduleParser.Sheet sh = new ScheduleParser.Sheet();
        sh.date = o.optString("date"); sh.weekday = o.optString("weekday"); sh.course = o.optString("course");
        JSONArray cols = o.optJSONArray("columns");
        for (int j = 0; cols != null && j < cols.length(); j++) {
          JSONObject co = cols.getJSONObject(j);
          ScheduleParser.Column c = new ScheduleParser.Column();
          c.groups = strings(co.optJSONArray("groups")); c.spec = co.optString("spec");
          JSONArray ls = co.optJSONArray("lessons");
          for (int k = 0; ls != null && k < ls.length(); k++) {
            JSONObject lo = ls.getJSONObject(k);
            ScheduleParser.Lesson l = new ScheduleParser.Lesson();
            l.num = lo.optInt("num"); l.time = lo.optString("time"); l.subject = lo.optString("subject"); l.teachers = strings(lo.optJSONArray("teachers"));
            c.lessons.add(l);
          }
          sh.columns.add(c);
        }
        sheets.add(sh);
      }
    } catch (JSONException ignored) {}
    return sheets;
  }

  /** Candidates from the teacher list for a name written in the schedule ("Саитова" or "Садыкова Д.Н."). */
  ArrayList<String[]> lookup(String written) { return lookup(bySurname, written); }

  static HashMap<String, ArrayList<String[]>> surnameMap(Context ctx) {
    HashMap<String, ArrayList<String[]>> m = new HashMap<>();
    try (BufferedReader r = new BufferedReader(new InputStreamReader(ctx.getAssets().open("teachers.tsv"), StandardCharsets.UTF_8))) {
      String line;
      while ((line = r.readLine()) != null) {
        String[] p = line.split("\t");
        if (p.length == 2) m.computeIfAbsent(norm(p[0].split(" ")[0]), k -> new ArrayList<>()).add(p);
      }
    } catch (IOException ignored) {}
    return m;
  }

  static ArrayList<String[]> lookup(HashMap<String, ArrayList<String[]>> bySurname, String written) {
    String[] w = written.trim().split("\\s+", 2);
    ArrayList<String[]> c = bySurname.get(norm(w[0]));
    if (c == null) return new ArrayList<>();
    if (w.length > 1 && c.size() > 1) {
      String ini = w[1].replaceAll("[^А-ЯЁа-яё]", "");
      ArrayList<String[]> f = new ArrayList<>();
      for (String[] t : c) {
        String[] n = t[0].split(" ");
        String tini = (n.length > 1 ? n[1].substring(0, 1) : "") + (n.length > 2 ? n[2].substring(0, 1) : "");
        if (!ini.isEmpty() && tini.toUpperCase(new Locale("ru")).startsWith(ini.toUpperCase(new Locale("ru")))) f.add(t);
      }
      if (!f.isEmpty()) return f;
    }
    return c;
  }

  static int[] minutes(String time) {
    Matcher m = Pattern.compile("(\\d{1,2}):(\\d{2})\\D+(\\d{1,2}):(\\d{2})").matcher(time);
    if (!m.find()) return null;
    return new int[]{Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)) * 60 + Integer.parseInt(m.group(4))};
  }

  void renderSchedule() {
    if (loading) { empty("Читаю расписание…", "Секунду"); return; }
    if (problem != null) {
      TextView warn = text(problem, 14, 0xFF7A4B00, false);
      warn.setBackground(round(0xFFFFF3D6, 14));
      warn.setPadding(dp(14), dp(12), dp(14), dp(12));
      warn.setLineSpacing(0, 1.15f);
      listBox.addView(warn, lp(-1, -2));
      LinearLayout row = new LinearLayout(this);
      TextView a = pill("Другой PDF", true, this::pickPdf);
      TextView b = pill("Скрыть", false, () -> { problem = null; renderList(); });
      LinearLayout.LayoutParams l1 = lp(0, -2); l1.weight = 1; l1.rightMargin = dp(5);
      LinearLayout.LayoutParams l2 = lp(0, -2); l2.weight = 1; l2.leftMargin = dp(5);
      row.addView(a, l1); row.addView(b, l2);
      listBox.addView(row);
      if (sheets.isEmpty()) return;
      View gap = new View(this); listBox.addView(gap, lp(-1, dp(10)));
    }
    if (sheets.isEmpty()) {
      empty("Расписания пока нет", "Поделись PDF с расписанием из MAX в «КНН пары» или выбери файл");
      listBox.addView(pill("Загрузить PDF", true, this::pickPdf));
      return;
    }
    String g = group();
    if (g.isEmpty()) {
      empty("Выбери свою группу", "Покажу только твои пары");
      listBox.addView(pill("Выбрать группу", true, this::groupDialog));
      listBox.addView(pill("Загрузить другой PDF", false, this::pickPdf));
      return;
    }
    ScheduleParser.Sheet sh = findSheet();
    if (sh == null) {
      ScheduleParser.Sheet last = lastLoaded.isEmpty() ? sheets.get(0) : lastLoaded.get(0);
      ArrayList<String> gl = knownGroups();
      empty("Группы " + g + " нет в расписании", "В PDF на " + last.date + (last.course.isEmpty() ? "" : " (" + last.course + " курс)") + " есть группы: " + TextUtils.join(", ", gl.subList(0, Math.min(12, gl.size()))) + ". Загрузи PDF своего курса или выбери другую группу.");
      listBox.addView(pill("Загрузить PDF", true, this::pickPdf));
      listBox.addView(pill("Сменить группу", false, this::groupDialog));
      return;
    }
    ScheduleParser.Column col = sh.find(g);
    long k = dayKey(sh.date);
    boolean isToday = k == todayKey(0);

    ArrayList<ScheduleParser.Sheet> days = daysForGroup();
    if (days.size() > 1) {
      HorizontalScrollView hs = new HorizontalScrollView(this);
      hs.setHorizontalScrollBarEnabled(false);
      LinearLayout chips = new LinearLayout(this);
      chips.setPadding(dp(1), 0, dp(1), dp(10));
      String[] wd = {"Вс", "Пн", "Вт", "Ср", "Чт", "Пт", "Сб"};
      for (ScheduleParser.Sheet d : days) {
        long dk = dayKey(d.date);
        String label = d.date.length() >= 5 ? d.date.substring(0, 5) : d.date;
        try {
          Calendar c = Calendar.getInstance(); c.set((int) (dk / 10000), (int) (dk / 100 % 100) - 1, (int) (dk % 100));
          label = wd[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + label;
        } catch (Exception ignored) {}
        if (dk == todayKey(0)) label = "Сегодня";
        else if (dk == todayKey(1)) label = "Завтра";
        boolean on = d == sh;
        TextView chip = text(label, 14, on ? Color.WHITE : PRIMARY, true);
        GradientDrawable cb = round(on ? PRIMARY : CARD, 12);
        if (!on) cb.setStroke(dp(1.5f), 0x551E4FD8);
        chip.setBackground(cb);
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        LinearLayout.LayoutParams cl = lp(-2, -2); cl.rightMargin = dp(8);
        chip.setOnClickListener(v -> { selectedDate = d.date; renderList(); });
        chips.addView(chip, cl);
      }
      hs.addView(chips);
      listBox.addView(hs, lp(-1, -2));
    }

    // day card
    LinearLayout day = new LinearLayout(this);
    day.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable dg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF15307F, PRIMARY});
    dg.setCornerRadius(dp(20));
    day.setBackground(dg);
    day.setPadding(dp(18), dp(16), dp(18), dp(16));
    LinearLayout dTop = new LinearLayout(this);
    dTop.setGravity(Gravity.CENTER_VERTICAL);
    String wd = sh.weekday.isEmpty() ? "" : sh.weekday.substring(0, 1).toUpperCase(new Locale("ru")) + sh.weekday.substring(1);
    TextView dTitle = text(wd.isEmpty() ? sh.date : wd + ", " + sh.date, 20, Color.WHITE, true);
    LinearLayout.LayoutParams dl = lp(0, -2); dl.weight = 1;
    dTop.addView(dTitle, dl);
    String badge = isToday ? "Сегодня" : k == todayKey(1) ? "Завтра" : k < todayKey(0) ? "Прошло" : "";
    if (!badge.isEmpty()) {
      TextView b = text(badge, 13, isToday ? GREEN : PRIMARY, true);
      b.setBackground(round(Color.WHITE, 12));
      b.setPadding(dp(10), dp(4), dp(10), dp(4));
      dTop.addView(b);
    }
    day.addView(dTop);
    StringBuilder meta = new StringBuilder("Группа " + g);
    if (!sh.course.isEmpty()) meta.append(" · ").append(sh.course).append(" курс");
    if (!col.spec.isEmpty()) meta.append(" · ").append(col.spec);
    meta.append(" · пар: ").append(col.lessons.size());
    TextView dm = text(meta.toString(), 14, 0xD9FFFFFF, false);
    dm.setPadding(0, dp(4), 0, 0);
    day.addView(dm);
    LinearLayout.LayoutParams dlp = lp(-1, -2); dlp.bottomMargin = dp(6); dlp.leftMargin = dp(1); dlp.rightMargin = dp(1);
    day.setElevation(dp(2));
    listBox.addView(day, dlp);

    if (col.lessons.isEmpty()) { empty("Пар нет", "Отдыхай"); }

    int now = -1;
    if (isToday) { Calendar c = Calendar.getInstance(); now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE); }
    boolean nextMarked = false;
    for (ScheduleParser.Lesson l : col.lessons) {
      String status = "";
      int[] mm = minutes(l.time);
      if (now >= 0 && mm != null) {
        if (now >= mm[0] && now < mm[1]) status = "Идёт сейчас";
        else if (now >= mm[1]) status = "done";
        else if (!nextMarked) { status = "Следующая"; nextMarked = true; }
        if (status.equals("Идёт сейчас")) nextMarked = true;
      }
      lessonCard(l, status);
    }

    LinearLayout row = new LinearLayout(this);
    TextView a = pill("Загрузить PDF", false, this::pickPdf);
    TextView b = pill("Сменить группу", false, this::groupDialog);
    LinearLayout.LayoutParams l1 = lp(0, -2); l1.weight = 1; l1.rightMargin = dp(5); l1.topMargin = dp(6);
    LinearLayout.LayoutParams l2 = lp(0, -2); l2.weight = 1; l2.leftMargin = dp(5); l2.topMargin = dp(6);
    row.addView(a, l1); row.addView(b, l2);
    listBox.addView(row);
    boolean auto = Reminders.auto(this);
    if (auto && !Reminders.canPopUp(this)) {
      TextView warn = text("Чтобы пары открывались сами, когда телефон разблокирован, разреши приложению «Поверх других приложений».", 14, 0xFF7A4B00, false);
      warn.setBackground(round(0xFFFFF3D6, 14));
      warn.setPadding(dp(14), dp(12), dp(14), dp(12));
      warn.setLineSpacing(0, 1.15f);
      LinearLayout.LayoutParams wl = lp(-1, -2); wl.topMargin = dp(14);
      listBox.addView(warn, wl);
      listBox.addView(pill("Разрешить автооткрытие", true, () -> {
        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)); }
      }));
    }
    listBox.addView(pill(auto ? "▶ Автовход на пары: включён" : "⏸ Автовход на пары: выключен", false, () -> {
      getSharedPreferences(PREFS, 0).edit().putBoolean(AUTO, !Reminders.auto(this)).apply();
      int n = Reminders.schedule(this);
      Toast.makeText(this, Reminders.auto(this) ? "Буду заходить сам за минуту до начала (пар: " + n + ")" : "Автовход выключен", Toast.LENGTH_SHORT).show();
      renderList();
    }));
    boolean rem = Reminders.enabled(this);
    listBox.addView(pill(rem ? "🔔 Напоминать о парах: включено" : "🔕 Напоминать о парах: выключено", false, () -> {
      getSharedPreferences(PREFS, 0).edit().putBoolean(REMIND, !Reminders.enabled(this)).apply();
      int n = Reminders.schedule(this);
      Toast.makeText(this, Reminders.enabled(this) ? "Напомню за " + Reminders.LEAD_MIN + " мин до пары (" + n + ")" : "Напоминания выключены", Toast.LENGTH_SHORT).show();
      renderList();
    }));
    TextView hint = text("Даты и время сверяй с официальным расписанием.", 12, MUTED, false);
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(0, dp(14), 0, 0);
    listBox.addView(hint, lp(-1, -2));
  }

  void lessonCard(ScheduleParser.Lesson l, String status) {
    boolean done = status.equals("done"), live = status.equals("Идёт сейчас");
    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    GradientDrawable bg = round(CARD, 18);
    if (live) bg.setStroke(dp(2), GREEN); else if (status.equals("Следующая")) bg.setStroke(dp(1.5f), 0x661E4FD8);
    card.setBackground(bg);
    card.setElevation(dp(1.5f));
    card.setPadding(dp(14), dp(14), dp(14), dp(10));
    if (done) card.setAlpha(0.55f);
    LinearLayout.LayoutParams p = lp(-1, -2); p.topMargin = dp(10); p.leftMargin = dp(1); p.rightMargin = dp(1);

    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView num = text(String.valueOf(l.num), 15, live ? Color.WHITE : PRIMARY, true);
    num.setGravity(Gravity.CENTER);
    GradientDrawable nc = new GradientDrawable(); nc.setShape(GradientDrawable.OVAL); nc.setColor(live ? GREEN : SOFT);
    num.setBackground(nc);
    top.addView(num, lp(dp(32), dp(32)));
    TextView time = text(l.time.replace("–", " – "), 15, TEXT, true);
    LinearLayout.LayoutParams tl = lp(0, -2); tl.weight = 1; tl.leftMargin = dp(10);
    top.addView(time, tl);
    if (!status.isEmpty() && !done) {
      TextView st = text(status, 12, live ? GREEN : PRIMARY, true);
      st.setBackground(round(live ? GREEN_SOFT : SOFT, 10));
      st.setPadding(dp(8), dp(3), dp(8), dp(3));
      top.addView(st);
    }
    card.addView(top);

    TextView subj = text(l.subject.isEmpty() ? "Пара" : l.subject, 17, TEXT, true);
    subj.setPadding(0, dp(10), 0, dp(4));
    subj.setLineSpacing(0, 1.1f);
    card.addView(subj);

    if (l.teachers.isEmpty()) {
      TextView n = text("Преподаватель не указан", 14, MUTED, false);
      n.setPadding(0, 0, 0, dp(4));
      card.addView(n);
    }
    for (String written : l.teachers) {
      ArrayList<String[]> cand = lookup(written);
      if (cand.isEmpty()) { card.addView(teacherRow(written, null, false)); continue; }
      for (String[] t : cand) card.addView(teacherRow(t[0], t[1], cand.size() > 1));
    }
    listBox.addView(card, p);
  }

  View teacherRow(String name, String url, boolean ambiguous) {
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(6), 0, dp(4));
    TextView av = avatar(initials(name), url == null ? STAR_OFF : AVATARS[Math.abs(name.hashCode()) % AVATARS.length]);
    av.setTextSize(12);
    av.setLayoutParams(lp(dp(30), dp(30)));
    row.addView(av);
    LinearLayout mid = new LinearLayout(this);
    mid.setOrientation(LinearLayout.VERTICAL);
    mid.addView(text(name, 14, TEXT, false));
    if (url == null) mid.addView(text("ссылки нет в списке", 12, MUTED, false));
    else if (ambiguous) mid.addView(text("однофамилец — проверь, кто ведёт", 12, MUTED, false));
    LinearLayout.LayoutParams ml = lp(0, -2); ml.weight = 1; ml.leftMargin = dp(10);
    row.addView(mid, ml);
    if (url != null) {
      TextView go = text("Войти", 14, Color.WHITE, true);
      go.setGravity(Gravity.CENTER);
      go.setBackground(ripple(round(PRIMARY, 12)));
      go.setPadding(dp(16), dp(9), dp(16), dp(9));
      go.setOnClickListener(v -> open(url, name));
      go.setOnLongClickListener(v -> { copy(url); return true; });
      row.addView(go);
    }
    return row;
  }
}
