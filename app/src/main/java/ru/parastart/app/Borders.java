package ru.parastart.app;

import android.graphics.Path;
import android.graphics.PointF;
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Collects the table borders drawn on a PDF page (thin filled rectangles and stroked lines), in top-down page coordinates. */
public class Borders extends PDFGraphicsStreamEngine {
  final List<ScheduleParser.Seg> segs = new ArrayList<>();
  private final List<float[]> rects = new ArrayList<>(), lines = new ArrayList<>();
  private final List<float[]> boxes = new ArrayList<>(); // bbox per subpath: x0,y0,x1,y1
  private float[] box;
  private final float top, left;
  private float cx, cy, sx, sy;

  public Borders(PDPage page) {
    super(page);
    PDRectangle box = page.getCropBox();
    top = box.getUpperRightY();
    left = box.getLowerLeftX();
  }

  public static List<ScheduleParser.Seg> of(PDPage page) {
    try { Borders b = new Borders(page); b.processPage(page); return b.segs; }
    catch (Throwable t) { return new ArrayList<>(); }
  }

  private void commit(boolean stroked) {
    for (float[] r : rects) ScheduleParser.addRect(segs, r[0] - left, top - r[1], r[2] - left, top - r[3], stroked);
    if (stroked) for (float[] l : lines) ScheduleParser.addLine(segs, l[0] - left, top - l[1], l[2] - left, top - l[3]);
    else for (float[] b : boxes) ScheduleParser.addRect(segs, b[0] - left, top - b[1], b[2] - left, top - b[3], false);
    clear();
  }

  private void clear() { rects.clear(); lines.clear(); boxes.clear(); box = null; }

  private void grow(float x, float y) {
    if (box == null) { box = new float[]{x, y, x, y}; boxes.add(box); return; }
    box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y); box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], y);
  }

  @Override public void appendRectangle(PointF p0, PointF p1, PointF p2, PointF p3) {
    float x0 = Math.min(Math.min(p0.x, p1.x), Math.min(p2.x, p3.x)), x1 = Math.max(Math.max(p0.x, p1.x), Math.max(p2.x, p3.x));
    float y0 = Math.min(Math.min(p0.y, p1.y), Math.min(p2.y, p3.y)), y1 = Math.max(Math.max(p0.y, p1.y), Math.max(p2.y, p3.y));
    rects.add(new float[]{x0, y0, x1, y1});
    cx = sx = p0.x; cy = sy = p0.y; box = null;
  }

  @Override public void moveTo(float x, float y) { cx = sx = x; cy = sy = y; box = null; grow(x, y); }
  @Override public void lineTo(float x, float y) { lines.add(new float[]{cx, cy, x, y}); cx = x; cy = y; grow(x, y); }
  @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { cx = x3; cy = y3; }
  @Override public PointF getCurrentPoint() { return new PointF(cx, cy); }
  @Override public void closePath() { if (cx != sx || cy != sy) lines.add(new float[]{cx, cy, sx, sy}); cx = sx; cy = sy; }
  @Override public void endPath() { clear(); }
  @Override public void strokePath() { commit(true); }
  @Override public void fillPath(Path.FillType windingRule) { commit(false); }
  @Override public void fillAndStrokePath(Path.FillType windingRule) { commit(true); }
  @Override public void clip(Path.FillType windingRule) {}
  @Override public void drawImage(PDImage pdImage) {}
  @Override public void shadingFill(COSName shadingName) {}
}
