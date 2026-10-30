package ru.knn.domashka;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.*;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** «КНН домашка»: the shared web UI in a WebView, plus access to the MAX folder, shared files and reminders. */
public class MainActivity extends Activity {
  static final String HOST = "appassets.androidplatform.net";
  static final String PREFS = "domashka";
  static final int PICK_FOLDER = 1, PICK_FILES = 2;

  WebView web;
  boolean pageReady;
  String pendingShow;

  @Override protected void onCreate(Bundle s) {
    super.onCreate(s);
    getWindow().setStatusBarColor(0xFF0A4F42);
    web = new WebView(this);
    WebSettings ws = web.getSettings();
    ws.setJavaScriptEnabled(true);
    ws.setDomStorageEnabled(true);
    ws.setAllowFileAccess(false);
    ws.setAllowContentAccess(false);
    ws.setMediaPlaybackRequiresUserGesture(true);
    web.setBackgroundColor(0xFFF1F5F3);
    web.addJavascriptInterface(new Bridge(), "AndroidBridge");
    web.setWebViewClient(new WebViewClient() {
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return serve(r.getUrl()); }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        Uri u = r.getUrl();
        if (HOST.equals(u.getHost())) return false;
        try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
        return true;
      }
      @Override public void onPageFinished(WebView v, String url) {
        pageReady = true;
        if (pendingShow != null) { emit("files-changed", null); emit("show-file", JSONObject.quote(pendingShow)); pendingShow = null; }
      }
    });
    setContentView(web);
    web.loadUrl("https://" + HOST + "/index.html");
    receive(getIntent());
  }

  @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); receive(i); }

  @Override public void onBackPressed() {
    web.evaluateJavascript("window.dzBack ? dzBack() : false", r -> { if (!"true".equals(r)) moveTaskToBack(true); });
  }

  // ---------- local web server ----------
  WebResourceResponse serve(Uri u) {
    if (!HOST.equals(u.getHost())) return null;
    String p = u.getPath() == null ? "/" : u.getPath();
    try {
      if (p.equals("/") || p.equals("/index.html")) return resp("text/html", getAssets().open("index.html"));
      if (p.startsWith("/f/")) {
        Uri doc = Uri.parse(new String(Base64.decode(p.substring(3), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8));
        String mime = getContentResolver().getType(doc);
        return resp(mime == null ? "application/octet-stream" : mime, getContentResolver().openInputStream(doc));
      }
      if (p.startsWith("/inbox/")) {
        File f = new File(inbox(), new File(Uri.decode(p.substring(7))).getName());
        return resp(mimeOf(f.getName()), new FileInputStream(f));
      }
    } catch (Exception e) {
      WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
      r.setStatusCodeAndReasonPhrase(404, "Not Found");
      return r;
    }
    return null;
  }

  static WebResourceResponse resp(String mime, InputStream in) {
    WebResourceResponse r = new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, in);
    Map<String, String> h = new HashMap<>();
    h.put("Cache-Control", "no-store");
    r.setResponseHeaders(h);
    return r;
  }

  static String mimeOf(String name) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
    if (n.endsWith(".png")) return "image/png";
    if (n.endsWith(".webp")) return "image/webp";
    if (n.endsWith(".gif")) return "image/gif";
    if (n.endsWith(".pdf")) return "application/pdf";
    if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    if (n.endsWith(".txt")) return "text/plain";
    return "application/octet-stream";
  }

  static boolean wanted(String name, String mime) {
    String n = name.toLowerCase(Locale.ROOT);
    if (n.startsWith(".")) return false;
    if (mime != null && (mime.startsWith("image/") || mime.equals("application/pdf") || mime.contains("wordprocessingml") || mime.equals("text/plain"))) return true;
    return n.matches(".*\\.(jpe?g|png|webp|gif|pdf|docx|txt)$");
  }

  File inbox() { File d = new File(getFilesDir(), "inbox"); d.mkdirs(); return d; }

  // ---------- folders ----------
  JSONArray folders() {
    try { return new JSONArray(getSharedPreferences(PREFS, 0).getString("folders", "[]")); } catch (JSONException e) { return new JSONArray(); }
  }

  void saveFolders(JSONArray a) { getSharedPreferences(PREFS, 0).edit().putString("folders", a.toString()).apply(); }

  static String label(Uri tree) {
    try {
      String id = DocumentsContract.getTreeDocumentId(tree);
      int i = id.indexOf(':');
      String path = i >= 0 ? id.substring(i + 1) : id;
      return path.isEmpty() ? "Память телефона" : path;
    } catch (Exception e) { return tree.toString(); }
  }

  JSONArray foldersJson() {
    JSONArray out = new JSONArray(), a = folders();
    for (int i = 0; i < a.length(); i++) {
      try { out.put(new JSONObject().put("id", a.getString(i)).put("label", label(Uri.parse(a.getString(i))))); } catch (JSONException ignored) {}
    }
    return out;
  }

  JSONArray list() {
    JSONArray out = new JSONArray();
    JSONArray a = folders();
    int[] budget = {1500};
    for (int i = 0; i < a.length(); i++) {
      try {
        Uri tree = Uri.parse(a.getString(i));
        walk(tree, DocumentsContract.getTreeDocumentId(tree), 0, out, budget);
      } catch (Exception ignored) {}
    }
    File[] in = inbox().listFiles();
    if (in != null) for (File f : in) {
      if (!wanted(f.getName(), null)) continue;
      try {
        out.put(new JSONObject().put("id", "inbox:" + f.getName()).put("name", f.getName().replaceFirst("^\\d+_", "")).put("mime", mimeOf(f.getName()))
            .put("size", f.length()).put("mtime", f.lastModified()).put("url", "https://" + HOST + "/inbox/" + Uri.encode(f.getName())));
      } catch (JSONException ignored) {}
    }
    return out;
  }

  void walk(Uri tree, String docId, int depth, JSONArray out, int[] budget) {
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
    String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE};
    List<String> dirs = new ArrayList<>();
    try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
      if (c == null) return;
      while (c.moveToNext() && budget[0] > 0) {
        String id = c.getString(0), name = c.getString(1), mime = c.getString(2);
        if (name == null) continue;
        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) { if (!name.startsWith(".")) dirs.add(id); continue; }
        if (!wanted(name, mime)) continue;
        budget[0]--;
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        String key = Base64.encodeToString(doc.toString().getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        try {
          out.put(new JSONObject().put("id", doc.toString()).put("name", name).put("mime", mime == null ? mimeOf(name) : mime)
              .put("size", c.isNull(4) ? 0 : c.getLong(4)).put("mtime", c.isNull(3) ? 0 : c.getLong(3)).put("url", "https://" + HOST + "/f/" + key));
        } catch (JSONException ignored) {}
      }
    } catch (Exception ignored) {}
    if (depth < 3) for (String d : dirs) if (budget[0] > 0) walk(tree, d, depth + 1, out, budget);
  }

  void pickFolder() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FMax"));
    try { startActivityForResult(i, PICK_FOLDER); } catch (Exception e) { toast("Не удалось открыть выбор папки"); }
  }

  void addFiles() {
    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.setType("*/*");
    i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "text/plain"});
    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    try { startActivityForResult(i, PICK_FILES); } catch (Exception e) { toast("Не удалось открыть выбор файлов"); }
  }

  @Override protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (res != RESULT_OK || data == null) return;
    if (req == PICK_FOLDER && data.getData() != null) {
      Uri tree = data.getData();
      try { getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
      JSONArray a = folders();
      boolean has = false;
      for (int i = 0; i < a.length(); i++) if (tree.toString().equals(a.optString(i))) has = true;
      if (!has) a.put(tree.toString());
      saveFolders(a);
      emit("folders", foldersJson().toString());
    } else if (req == PICK_FILES) {
      List<Uri> uris = new ArrayList<>();
      if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
      else if (data.getData() != null) uris.add(data.getData());
      importAsync(uris, null);
    }
  }

  // ---------- files shared from MAX ----------
  void receive(Intent i) {
    if (i == null) return;
    String a = i.getAction();
    List<Uri> uris = new ArrayList<>();
    if (Intent.ACTION_SEND.equals(a)) {
      Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM);
      if (u != null) uris.add(u);
      else if (i.getStringExtra(Intent.EXTRA_TEXT) != null) {
        importText(i.getStringExtra(Intent.EXTRA_TEXT));
        return;
      }
    } else if (Intent.ACTION_SEND_MULTIPLE.equals(a)) {
      ArrayList<Uri> l = i.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
      if (l != null) uris.addAll(l);
    }
    if (!uris.isEmpty()) importAsync(uris, "Добавлено в «КНН домашку»");
  }

  void importText(String text) {
    try {
      File f = new File(inbox(), System.currentTimeMillis() + "_Задание из MAX.txt");
      try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) { w.write(text); }
      show("inbox:" + f.getName());
    } catch (IOException e) { toast("Не получилось сохранить текст"); }
  }

  void importAsync(List<Uri> uris, String msg) {
    new Thread(() -> {
      String first = null;
      for (Uri u : uris) {
        String name = displayName(u);
        if (!wanted(name, getContentResolver().getType(u))) {
          String ext = extFor(getContentResolver().getType(u));
          if (ext == null) continue;
          name = name + ext;
        }
        File f = new File(inbox(), System.currentTimeMillis() + "_" + name.replaceAll("[\\\\/:*?\"<>|]", "_"));
        try (InputStream in = getContentResolver().openInputStream(u); OutputStream out = new FileOutputStream(f)) {
          byte[] b = new byte[65536]; int n;
          while ((n = in.read(b)) > 0) out.write(b, 0, n);
          if (first == null) first = "inbox:" + f.getName();
        } catch (Exception e) { f.delete(); }
      }
      String show = first;
      runOnUiThread(() -> {
        if (show == null) { toast("Этот файл не поддерживается: нужны фото, PDF, Word или текст"); return; }
        if (msg != null) toast(msg);
        show(show);
      });
    }).start();
  }

  void show(String id) {
    if (pageReady) { emit("files-changed", null); emit("show-file", JSONObject.quote(id)); }
    else pendingShow = id;
  }

  String displayName(Uri u) {
    try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
      if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
    } catch (Exception ignored) {}
    String s = u.getLastPathSegment();
    return s == null ? "Файл" : s;
  }

  static String extFor(String mime) {
    if (mime == null) return null;
    if (mime.equals("image/jpeg")) return ".jpg";
    if (mime.equals("image/png")) return ".png";
    if (mime.equals("image/webp")) return ".webp";
    if (mime.equals("application/pdf")) return ".pdf";
    if (mime.contains("wordprocessingml")) return ".docx";
    if (mime.equals("text/plain")) return ".txt";
    return null;
  }

  // ---------- bridge ----------
  void emit(String name, String json) {
    web.evaluateJavascript("window.nativeEvent && nativeEvent(" + JSONObject.quote(name) + "," + (json == null ? "null" : json) + ")", null);
  }

  void reply(String id, String json) {
    runOnUiThread(() -> web.evaluateJavascript("window.nativeResult(" + JSONObject.quote(id) + "," + (json == null ? "null" : json) + ")", null));
  }

  void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show()); }

  class Bridge {
    @JavascriptInterface public void call(String id, String method, String argsJson) {
      new Thread(() -> {
        String result = "null";
        try {
          JSONObject args = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
          switch (method) {
            case "hello": result = new JSONObject().put("platform", "android").toString(); break;
            case "folders": result = foldersJson().toString(); break;
            case "list": result = list().toString(); break;
            case "pickFolder": runOnUiThread(MainActivity.this::pickFolder); break;
            case "addFiles": runOnUiThread(MainActivity.this::addFiles); break;
            case "removeFolder": {
              String f = args.optString("id");
              JSONArray a = folders(), b = new JSONArray();
              for (int i = 0; i < a.length(); i++) if (!f.equals(a.optString(i))) b.put(a.optString(i));
              saveFolders(b);
              try { getContentResolver().releasePersistableUriPermission(Uri.parse(f), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
              result = "true";
              break;
            }
            case "open": openExternal(args.optString("id")); break;
            case "setReminders": Reminders.set(MainActivity.this, args.optJSONArray("items")); result = "true"; break;
          }
        } catch (Exception e) { result = "null"; }
        reply(id, result);
      }).start();
    }
  }

  void openExternal(String id) {
    try {
      Uri u;
      String mime;
      if (id.startsWith("inbox:")) {
        File f = new File(inbox(), id.substring(6));
        u = FileProvider.getUriForFile(this, "ru.knn.domashka.files", f);
        mime = mimeOf(f.getName());
      } else {
        u = Uri.parse(id);
        mime = getContentResolver().getType(u);
      }
      Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(Intent.createChooser(i, "Открыть в…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    } catch (Exception e) { toast("Нет приложения, чтобы открыть этот файл"); }
  }
}
