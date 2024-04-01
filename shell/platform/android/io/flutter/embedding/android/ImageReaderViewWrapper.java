package io.flutter.embedding.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import io.flutter.Log;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView;

public class ImageReaderViewWrapper extends FrameLayout {
  public ImageReaderViewWrapper(@NonNull Context context) {
    super(context);
    init();
  }

  public ImageReaderViewWrapper(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public ImageReaderViewWrapper(
      @NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  FlutterMutatorView platformView;
  ImageReaderView overlayView;

  int viewId;

  FrameManager.PlatformViewInfo info;

  void init() {

    //        setClickable(false);
    //        setFocusable(false);
    //        setOnTouchListener(new OnTouchListener() {
    //            @Override
    //            public boolean onTouch(View view, MotionEvent motionEvent) {
    //                return false;
    //            }
    //        });
  }

  public void removePlatformView() {
    if (platformView != null) {
      removeView(platformView);
      platformView = null;
    }
  }

  void setPlatformView(FlutterMutatorView view) {
    if (platformView != null) {
      removeView(platformView);
    }
    platformView = view;
    if (info != null) {
      updatePlatformViewInfo(info);
    }
    Log.e(
        "Flutter",
        "setPlatformView MutatorsStack " + (info == null ? null : info.getMutatorsStack()));
    addView(view, 0);
  }

  void setOverlayView(ImageReaderView view) {
    if (overlayView != null) {
      removeView(overlayView);
    }
    overlayView = view;
    addView(view);
  }

  void setOverlayVisible(boolean visible) {
    if (overlayView != null) {
      if (visible) {
        overlayView.setVisibility(View.VISIBLE);
      } else {
        overlayView.setVisibility(View.GONE);
      }
    }
  }

  void updatePlatformViewInfo(FrameManager.PlatformViewInfo info) {
    if (platformView != null) {
      platformView.readyToDisplay(
          info.getMutatorsStack(),
          info.getLeft(),
          info.getTop(),
          info.getWidth(),
          info.getHeight());
      platformView.invalidate();
    }
  }

  FrameLayout.LayoutParams _getLayoutParam(FrameManager.PlatformViewInfo info) {
    FrameLayout.LayoutParams layoutParam =
        new FrameLayout.LayoutParams(info.getWidth(), info.getHeight());
    layoutParam.topMargin = info.getTop();
    layoutParam.leftMargin = info.getLeft();
    if (info.getWidth() == -1 && info.getHeight() == -1) {
      layoutParam.width = LayoutParams.MATCH_PARENT;
      layoutParam.height = LayoutParams.MATCH_PARENT;
      if (info.getTop() == 0 && info.getLeft() == 0) {
        layoutParam.topMargin = 0;
        layoutParam.leftMargin = 0;
        layoutParam.bottomMargin = 0;
        layoutParam.rightMargin = 0;
      }
    }
    return layoutParam;
  }

  void updateInfo(FrameManager.PlatformViewInfo info) {
    this.info = info;
    if (overlayView != null) {
      overlayView.currentBitmap = info.getBitmap();
      overlayView.setLayoutParams(_getLayoutParam(info));
      overlayView.invalidate();
    }
    updatePlatformViewInfo(info);
    setOverlayVisible(info.isHaveOverlay());
  }

  public static class ImageReaderView extends View {
    public Bitmap currentBitmap;

    public ImageReaderView(Context context) {
      super(context);
      init();
    }

    public ImageReaderView(Context context, @Nullable AttributeSet attrs) {
      super(context, attrs);
      init();
    }

    public ImageReaderView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
      super(context, attrs, defStyleAttr);
      init();
    }

    void init() {
      setClickable(false);
      setFocusable(false);
      setOnTouchListener(
          new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
              return false;
            }
          });
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
      super.onDraw(canvas);
      Log.e("Flutter", "ImageReaderView onDraw bitmap " + currentBitmap);
      if (currentBitmap != null) {
        canvas.drawBitmap(currentBitmap, 0, 0, null);
      }
    }
  }
}
