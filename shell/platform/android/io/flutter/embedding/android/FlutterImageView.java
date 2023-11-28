// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.android;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.Image.Plane;
import android.media.ImageReader;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.Surface;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import io.flutter.Log;
import io.flutter.embedding.engine.renderer.FlutterRenderer;
import io.flutter.embedding.engine.renderer.RenderSurface;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Paints a Flutter UI provided by an {@link android.media.ImageReader} onto a {@link
 * android.graphics.Canvas}.
 *
 * <p>A {@code FlutterImageView} is intended for situations where a developer needs to render a
 * Flutter UI, but also needs to render an interactive {@link
 * io.flutter.plugin.platform.PlatformView}.
 *
 * <p>This {@code View} takes an {@link android.media.ImageReader} that provides the Flutter UI in
 * an {@link android.media.Image} and renders it to the {@link android.graphics.Canvas} in {@code
 * onDraw}.
 */
@TargetApi(19)
public class FlutterImageView extends View implements RenderSurface {
  private static final String TAG = "FlutterImageView";

  @NonNull private ImageReader imageReader;
  @Nullable private Bitmap currentBitmap;
  @Nullable private FlutterRenderer flutterRenderer;
  @NonNull private final Set<Runnable> onImageAvailableListeners = new HashSet<>();

  @NonNull
  private final ImageReader.OnImageAvailableListener onImageAvailableListener =
      new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
          for (Runnable listener : onImageAvailableListeners) {
            listener.run();
          }
        }
      };

  /** Image pending to be draw. */
  @Nullable private Image pendingImage;
  /** An Image that needs to be closed once the associated Bitmap is no longer in use. */
  @Nullable private Image pendingCloseImage;

  final int maxAcquiredImages = 3;
  ArrayDeque<Image> acquiredImages = new ArrayDeque<>();

  public ImageReader getImageReader() {
    return imageReader;
  }

  public enum SurfaceKind {
    /** Displays the background canvas. */
    background,

    /** Displays the overlay surface canvas. */
    overlay,
  }

  /** The kind of surface. */
  private SurfaceKind kind;

  /** Whether the view is attached to the Flutter render. */
  private boolean isAttachedToFlutterRenderer = false;

  /**
   * Constructs a {@code FlutterImageView} with an {@link android.media.ImageReader} that provides
   * the Flutter UI.
   */
  public FlutterImageView(@NonNull Context context, int width, int height, SurfaceKind kind) {
    this(context, createImageReader(width, height), kind);
  }

  public FlutterImageView(@NonNull Context context) {
    this(context, 1, 1, SurfaceKind.background);
  }

  public FlutterImageView(@NonNull Context context, @NonNull AttributeSet attrs) {
    this(context, 1, 1, SurfaceKind.background);
  }

  @VisibleForTesting
  /*package*/ FlutterImageView(
      @NonNull Context context, @NonNull ImageReader imageReader, SurfaceKind kind) {
    super(context, null);
    this.imageReader = imageReader;
    setOnImageAvailableListener();
    this.kind = kind;
    init();
  }

  private void init() {
    setAlpha(0.0f);
  }

  private static void logW(String format, Object... args) {
    Log.w(TAG, String.format(Locale.US, format, args));
  }

  private void setOnImageAvailableListener() {
    if (imageReader != null) {
      imageReader.setOnImageAvailableListener(onImageAvailableListener, null);
    }
  }

  @TargetApi(19)
  @SuppressLint("WrongConstant") // RGBA_8888 is a valid constant.
  @NonNull
  private static ImageReader createImageReader(int width, int height) {
    if (width <= 0) {
      logW("ImageReader width must be greater than 0, but given width=%d, set width=1", width);
      width = 1;
    }
    if (height <= 0) {
      logW("ImageReader height must be greater than 0, but given height=%d, set height=1", height);
      height = 1;
    }
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      return ImageReader.newInstance(
          width,
          height,
          PixelFormat.RGBA_8888,
          6,
          HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
    } else {
      return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 6);
    }
  }

  @NonNull
  public Surface getSurface() {
    return imageReader.getSurface();
  }

  @Nullable
  @Override
  public FlutterRenderer getAttachedRenderer() {
    return flutterRenderer;
  }

  /**
   * Invoked by the owner of this {@code FlutterImageView} when it wants to begin rendering a
   * Flutter UI to this {@code FlutterImageView}.
   */
  @Override
  public void attachToRenderer(@NonNull FlutterRenderer flutterRenderer) {
    switch (kind) {
      case background:
        flutterRenderer.swapSurface(imageReader.getSurface());
        flutterRenderer.SetRenderingToImageView(true);
        break;
      case overlay:
        // Do nothing since the attachment is done by the handler of
        // `FlutterJNI#createOverlaySurface()` in the native side.
        break;
    }
    setAlpha(1.0f);
    this.flutterRenderer = flutterRenderer;
    isAttachedToFlutterRenderer = true;
  }

  /**
   * Invoked by the owner of this {@code FlutterImageView} when it no longer wants to render a
   * Flutter UI to this {@code FlutterImageView}.
   */
  public void detachFromRenderer() {
    if (!isAttachedToFlutterRenderer) {
      return;
    }
    setAlpha(0.0f);
    // Drop the latest image as it shouldn't render this image if this view is
    // attached to the renderer again.
    acquireLatestImage();
    // Clear drawings.
    currentBitmap = null;

    // Close and clear the current image if any.
    closeAllImages();
    invalidate();
    isAttachedToFlutterRenderer = false;
    if (kind == SurfaceKind.background) {
      // The overlay FlutterImageViews seem to be constructed per frame and not
      // always used; An overlay FlutterImageView always seems to imply
      // a background FlutterImageView.
      flutterRenderer.SetRenderingToImageView(false);
    }
  }

  public void pause() {
    // Not supported.
  }

  /**
   * Acquires the next image to be drawn to the {@link android.graphics.Canvas}. Returns true if
   * there's an image available in the queue.
   */
  @TargetApi(19)
  public boolean acquireLatestImage() {
    if (!isAttachedToFlutterRenderer) {
      return false;
    }
    closeImagesIfNeeded();
    // 1. `acquireLatestImage()` may return null if no new image is available.
    // 2. There's no guarantee that `onDraw()` is called after `invalidate()`.
    // For example, the device may not produce new frames if it's in sleep mode
    // or some special Android devices so the calls to `invalidate()` queued up
    // until the device produces a new frame.
    // 3. While the engine will also stop producing frames, there is a race condition.
    final Image newImage = imageReader.acquireLatestImage();
    if (newImage != null) {
      // Put the image in a queue
      acquiredImages.offer(newImage);
      if (acquiredImages.size() > maxAcquiredImages) {
        Image image = acquiredImages.pollFirst();
        if (image != null) {
          image.close();
        }
      }
      if (pendingImage == null) {
        pendingImage = acquiredImages.pollFirst();
      }
      invalidate();
    }
    return newImage != null || pendingImage != null;
  }

  public @Nullable Image getPendingImage() {
    return pendingImage;
  }

  public boolean getIsAttachedToRenderer() {
    return isAttachedToFlutterRenderer;
  }

  /** Creates a new image reader with the provided size. */
  public void resizeIfNeeded(int width, int height) {
    if (flutterRenderer == null) {
      return;
    }
    if (width == imageReader.getWidth() && height == imageReader.getHeight()) {
      return;
    }

    // Close resources.
    closeAllImages();
    // Close the current image reader, then create a new one with the new size.
    // Image readers cannot be resized once created.
    closeImageReader();
    imageReader = createImageReader(width, height);
    setOnImageAvailableListener();
  }

  /**
   * Closes the image reader associated with the current {@code FlutterImageView}.
   *
   * <p>Once the image reader is closed, calling {@code acquireLatestImage} will result in an {@code
   * IllegalStateException}.
   */
  public void closeImageReader() {
    closeAllImages();
    imageReader.close();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (pendingImage != null) {
      updateCurrentBitmap(pendingImage);
      if (pendingCloseImage != null) {
        pendingCloseImage.close();
      }
      pendingCloseImage = pendingImage;
      pendingImage = null;
      if (!acquiredImages.isEmpty()) {
        // Process the next image
        Choreographer.FrameCallback frameCallback =
            new Choreographer.FrameCallback() {
              @Override
              public void doFrame(long time) {
                if (pendingImage == null && !acquiredImages.isEmpty()) {
                  pendingImage = acquiredImages.pollFirst();
                  onImageAvailableListener.onImageAvailable(imageReader);
                }
              }
            };
        Choreographer.getInstance().postFrameCallback(frameCallback);
        invalidate();
      }
    }
    if (currentBitmap != null) {
      canvas.drawBitmap(currentBitmap, 0, 0, null);
    }
  }

  private void closeAllImages() {
    while (!acquiredImages.isEmpty()) {
      Image image = acquiredImages.poll();
      if (image != null) {
        image.close();
      }
    }
    if (pendingImage != null) {
      pendingImage.close();
      pendingImage = null;
    }
    if (pendingCloseImage != null) {
      pendingCloseImage.close();
      pendingCloseImage = null;
    }
  }

  private void closeImagesIfNeeded() {
    if (imageReader != null) {
      while (!acquiredImages.isEmpty() && (countOpenedImages() > imageReader.getMaxImages() - 2)) {
        Image image = acquiredImages.pollFirst();
        if (image != null) {
          image.close();
        }
      }
    }
  }

  private int countOpenedImages() {
    return acquiredImages.size()
        + (pendingImage != null ? 1 : 0)
        + (pendingCloseImage != null ? 1 : 0);
  }

  @TargetApi(29)
  private void updateCurrentBitmap(@NonNull Image currentImage) {
    if (android.os.Build.VERSION.SDK_INT >= 29) {
      final HardwareBuffer buffer = currentImage.getHardwareBuffer();
      currentBitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
      buffer.close();
    } else {
      final Plane[] imagePlanes = currentImage.getPlanes();
      if (imagePlanes.length != 1) {
        return;
      }

      final Plane imagePlane = imagePlanes[0];
      final int desiredWidth = imagePlane.getRowStride() / imagePlane.getPixelStride();
      final int desiredHeight = currentImage.getHeight();

      if (currentBitmap == null
          || currentBitmap.getWidth() != desiredWidth
          || currentBitmap.getHeight() != desiredHeight) {
        currentBitmap =
            Bitmap.createBitmap(
                desiredWidth, desiredHeight, android.graphics.Bitmap.Config.ARGB_8888);
      }
      ByteBuffer buffer = imagePlane.getBuffer();
      buffer.rewind();
      currentBitmap.copyPixelsFromBuffer(buffer);
    }
  }

  @Override
  protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
    if (width == imageReader.getWidth() && height == imageReader.getHeight()) {
      return;
    }
    // `SurfaceKind.overlay` isn't resized. Instead, the `FlutterImageView` instance
    // is destroyed. As a result, an instance with the new size is created by the surface
    // pool in the native side.
    if (kind == SurfaceKind.background && isAttachedToFlutterRenderer) {
      resizeIfNeeded(width, height);
      // Bind native window to the new surface, and create a new onscreen surface
      // with the new size in the native side.
      flutterRenderer.swapSurface(imageReader.getSurface());
    }
  }

  /**
   * Registers a callback to be notified when a new image becomes available.
   *
   * @param listener The listener to be added for new image notifications. Must not be null.
   */
  public void addOnImageAvailableListener(@NonNull Runnable listener) {
    onImageAvailableListeners.add(listener);
  }

  /**
   * Removes a callback previously added by {@code addOnImageAvailableListener}.
   *
   * @param listener The listener to be removed.
   */
  public void removeOnImageAvailableListener(@NonNull Runnable listener) {
    onImageAvailableListeners.remove(listener);
  }
}