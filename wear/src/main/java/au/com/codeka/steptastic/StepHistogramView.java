package au.com.codeka.steptastic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import java.util.Random;

public class StepHistogramView extends View {
  private int[] histogram;

  Paint axisPaint = new Paint();
  Paint columnPaint = new Paint();
  Rect rect = new Rect();


  public StepHistogramView(Context context, AttributeSet attrSet) {
    super(context, attrSet);

    if (isInEditMode()) {
      histogram = new int[24];
      Random r = new Random();
      for (int i = 0; i < 24; i++) {
        histogram[i] = 100 + r.nextInt(100);
      }
    }

    axisPaint.setColor(Color.WHITE);
    axisPaint.setStrokeWidth(3);
    columnPaint.setColor(0xff006600);
    columnPaint.setStrokeWidth(4);
  }

  public void setHistogram(int[] histogram) {
    this.histogram = histogram;
    invalidate();
  }

  @Override
  public void onDraw(Canvas canvas) {
    if (histogram == null) {
      return;
    }

    canvas.getClipBounds(rect);
    rect.bottom -= 3;
    canvas.drawLine(rect.left, rect.bottom, rect.width(), rect.height(), axisPaint);

    float max = 0;
    for (int i = 0; i < 24; i++) {
      max = Math.max(histogram[i], max);
    }

    float columnWidth = rect.width() / 24.0f;
    for (int i = 0; i < 24; i++) {
      float x = columnWidth * i + (columnWidth / 2.0f);
      float top = rect.top + (rect.height() * (1.0f - (histogram[i] / max)));
      canvas.drawLine(x, top, x, rect.height(), columnPaint);
    }
  }
}
