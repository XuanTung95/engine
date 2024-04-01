package io.flutter.embedding.android;



import android.graphics.Matrix;
import android.view.Surface;
import android.widget.FrameLayout;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

import io.flutter.Log;
import io.flutter.embedding.android.AndroidTouchProcessor;
import io.flutter.embedding.engine.FlutterOverlaySurface;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorView;
import io.flutter.embedding.engine.mutatorsstack.FlutterMutatorsStack;

public class ImageReaderPlatformViewManager {
    private final ImageProducer imageProducer;
    private final FrameManager frameManager;
    private final ViewManager viewManager;

    public ImageReaderPlatformViewManager() {
        viewManager = new ViewManager();
        frameManager = new FrameManager(viewManager);
        imageProducer = new ImageProducer(frameManager);
    }

    public void onPlatformViewCreated(int viewId, FlutterMutatorView platformView) {
        viewManager.onPlatformViewCreated(viewId, platformView);
    }

    public boolean containPlatformView(int viewId) {
        return viewManager.containPlatformView(viewId);
    }

    public void disposeView(int viewId) {
        viewManager.disposeView(viewId);
    }

    public void attachToView(FlutterView flutterView) {
        imageProducer.setFlutterView(flutterView);
        viewManager.setFlutterView(flutterView);
    }

    public void detachFromView() {

    }

    public FlutterOverlaySurface createImageReader(int id, int width, int height, int left, int top) {
        return imageProducer.createOrResizeImageReader(id, width, height, left, top);
    }

    public void addNewFrameInfo(long rasterStart, int[] viewIds) {
        frameManager.addNewFrameInfo(rasterStart, viewIds);
    }

    public void updateFrameInfo(int viewId, long rasterStart, int width, int height, int top, int left, boolean haveOverlay, FlutterMutatorsStack mutatorsStack) {
        frameManager.updateFrameInfo(viewId, rasterStart, width, height, top, left, haveOverlay, mutatorsStack);
    }

    public void release() {
        imageProducer.release();
        frameManager.release();
        viewManager.release();
    }
}

class ViewManager {
    private final ArrayList<Runnable> onAvailableCallback = new ArrayList<>();
    private FlutterView flutterView;
    private FrameManager.FrameInfo currentFrame;
    private FrameManager.FrameInfo pendingEmptyFrame;
    private final SparseArray<FlutterMutatorView> platformViews = new SparseArray<>();
    private final SparseArray<ImageReaderViewWrapper> viewWrappers = new SparseArray<>();

    public boolean available() { return currentFrame == null;}
    public void addOnAvailableCallback(Runnable callback) {
        onAvailableCallback.add(callback);
    }

    public void removeOnAvailableCallback(Runnable callback) {
        onAvailableCallback.remove(callback);
    }

    boolean containPlatformView(int viewId) {
        return platformViews.get(viewId) != null;
    }

    public void disposeView(int viewId) {
        FlutterMutatorView view = platformViews.get(viewId);
        if (view != null) {
            view.removeAllViews();
            platformViews.remove(viewId);
        }
    }

    public void setFlutterView(FlutterView flutterView) {
        this.flutterView = flutterView;
    }

    public void processNextFrame(FrameManager.FrameInfo frame) {
        if (frame.views.length == 0 && pendingEmptyFrame == null) {
            pendingEmptyFrame = frame;
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long l) {
                    if (pendingEmptyFrame != null) {
                        processNextFrameInternal(pendingEmptyFrame);
                    }
                }
            });
            return;
        }
        processNextFrameInternal(frame);
    }

    void processNextFrameInternal(FrameManager.FrameInfo frame) {
        currentFrame = frame;
        pendingEmptyFrame = null;
        createViews(frame);
        Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long l) {
                currentFrame = null;
                for (Runnable item : onAvailableCallback) {
                    item.run();
                }
                Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                    @Override
                    public void doFrame(long l) {
                        frame.release();
                    }
                });
            }
        });
    }

    public void onPlatformViewCreated(int viewId, FlutterMutatorView platformView) {
        platformViews.set(viewId, platformView);
        ImageReaderViewWrapper viewWrapper = viewWrappers.get(viewId);
        if (viewWrapper != null) {
            viewWrapper.setPlatformView(platformView);
        }
    }

    private void createViews(FrameManager.FrameInfo frame) {
        Log.e("Flutter", "createViews frame " + frame);
        int childCount = flutterView.getChildCount();
        ArrayList<View> otherViews = new ArrayList<>();
        ArrayList<ImageReaderViewWrapper> keepViews = new ArrayList<>();
        ArrayList<ImageReaderViewWrapper> deleteViews = new ArrayList<>();
        for (int i=0; i < childCount; i++) {
            View child = flutterView.getChildAt(i);
            if (child instanceof ImageReaderViewWrapper) {
                ImageReaderViewWrapper curr = (ImageReaderViewWrapper) child;
                boolean _delete = true;
                for (int j = 0; j < frame.views.length; j++) {
                    FrameManager.PlatformViewInfo item = frame.views[j];
                    if (item.getViewId() == curr.viewId) {
                        _delete = false;
                        keepViews.add(curr);
                    }
                }
                if (_delete) {
                    deleteViews.add(curr);
                }
            } else {
                otherViews.add(child);
            }
        }
        // delete view
        if (!deleteViews.isEmpty()) {
            for (ImageReaderViewWrapper item : deleteViews) {
                item.removePlatformView();
                flutterView.removeView(item);
                // platformViews.remove(item.viewId);
                viewWrappers.remove(item.viewId);
            }
        }
        ArrayList<View> newOrder = new ArrayList<>(otherViews);
        // new order
        for (int i = 0; i < frame.views.length; i++) {
            FrameManager.PlatformViewInfo info = frame.views[i];
            boolean found = false;
            for (ImageReaderViewWrapper item : keepViews) {
                if (item.viewId == info.getViewId()) {
                    found = true;
                    item.updateInfo(info);
                    newOrder.add(item);
                    break;
                }
            }
            if (!found) {
                // create new view
                newOrder.add(createNewViewWrapper(info));
            }
        }

        // order
        for (int i = 0; i < newOrder.size(); i++) {
            View currView = newOrder.get(i);
            int currentId = flutterView.indexOfChild(currView);
            if (currentId < 0) {
                flutterView.addView(currView, i);
            } else {
                if (i != currentId) {
                    flutterView.removeView(currView);
                    flutterView.addView(currView, i);
                }
            }
        }
    }

    private ImageReaderViewWrapper createNewViewWrapper(FrameManager.PlatformViewInfo info) {
        ImageReaderViewWrapper view = new ImageReaderViewWrapper(flutterView.getContext());
        view.viewId = info.getViewId();
        view.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        view.setOverlayView(new ImageReaderViewWrapper.ImageReaderView(flutterView.getContext()));
        view.updateInfo(info);
        if (platformViews.get(info.getViewId()) != null) {
            view.setPlatformView(platformViews.get(info.getViewId()));
        }
        viewWrappers.set(info.getViewId(), view);
        return view;
    }

    public void release() {
        onAvailableCallback.clear();
        platformViews.clear();
        viewWrappers.clear();
    }
}

class ImageProducer {
    static final int MAX_IMAGES = 7;
    static final int KEEP_OPEN_IMAGES = 3;
    private final FrameManager frameManager;
    private int nextOverlayLayerId = 0;

    private FrameLayout flutterView;
    private final SparseArray<ImageReaderData> imageReaders = new SparseArray<>();

    public ImageProducer(FrameManager frameManager) {
        this.frameManager = frameManager;
    }

    public interface ReleaseImageCallback {
        void releaseImage();
    }

    public void setFlutterView(FrameLayout flutterView) {
        this.flutterView = flutterView;
    }

    synchronized public FlutterOverlaySurface createOrResizeImageReader(int id, int width, int height, int left, int top) {
        if (width == -1) {
            width = flutterView.getWidth();
        }
        if (height == -1) {
            height = flutterView.getHeight();
        }
        ImageReaderData data = imageReaders.get(id);
        if (data == null) {
            data = new ImageReaderData();
            imageReaders.set(id, data);
        }
        ImageReaderData.ImageReaderItem imageReaderItem = data.getImageReaderFor(width, height);
        if (imageReaderItem == null) {
            data.closeAllImageReader();
            ImageReader imageReader = createImageReader(width, height);
            final int imageReaderId = nextOverlayLayerId++;
            imageReaderItem = new ImageReaderData.ImageReaderItem(imageReaderId);
            imageReaderItem.imageReader = imageReader;
            data.addImageReader(imageReaderItem);
            ImageReaderData.ImageReaderItem finalImageReaderItem = imageReaderItem;
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader imageReader) {
                    Image image;
                    do {
                        finalImageReaderItem.closeImageIfNeeded();
                        image = imageReader.acquireNextImage();
                        if (image != null) {
                            onImageCreated(id, imageReader, finalImageReaderItem, image);
                        }
                    } while (image != null);
                }
            }, new Handler(Looper.getMainLooper()));
        }
        return new FlutterOverlaySurface(imageReaderItem.imageReaderId, imageReaderItem.imageReader.getSurface());
    }

    private ImageReader createImageReader(int width, int height) {
        if (width <= 0) {
            width = 1;
        }
        if (height <= 0) {
            height = 1;
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            return ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    MAX_IMAGES,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        } else {
            return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES);
        }
    }

    private void onImageCreated(int viewId, ImageReader imageReader, ImageReaderData.ImageReaderItem imageReaderItem, Image image) {
        if (isImageReaderClosed(viewId, imageReader)) {
            return;
        }
        Log.e("Flutter", "onImageCreated viewId " + viewId);
        long imageTimestamp;
        Bitmap bitmap;
        try {
            imageTimestamp = image.getTimestamp();
            imageReaderItem.addImage(image);
            bitmap = createBitmap(image, null);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }
        frameManager.onBitmapCreated(viewId, imageTimestamp, bitmap, new ReleaseImageCallback() {
            @Override
            public void releaseImage() {
                imageReaderItem.closeImage(image);
            }
        });
    }

    private boolean isImageReaderClosed(int viewId, ImageReader imageReader) {
        ImageReaderData data = imageReaders.get(viewId);
        if (data == null) {
            return true;
        }
        return data.isImageReaderClosed(imageReader);
    }

    private Bitmap createBitmap(Image image, Bitmap currentBitmap) {
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            final HardwareBuffer buffer = image.getHardwareBuffer();
            currentBitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
            buffer.close();
        } else {
            final Image.Plane[] imagePlanes = image.getPlanes();
            if (imagePlanes.length != 1) {
                return null;
            }

            final Image.Plane imagePlane = imagePlanes[0];
            final int desiredWidth = imagePlane.getRowStride() / imagePlane.getPixelStride();
            final int desiredHeight = image.getHeight();

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
        return currentBitmap;
    }

    synchronized public void release() {
        for (int i = 0; i < imageReaders.size(); i++) {
            int key = imageReaders.keyAt(i);
            ImageReaderData value = imageReaders.get(key);
            if (value != null) {
                value.closeAllImageReader();
            }
        }
        imageReaders.clear();
    }
}

class FrameManager {
    private final ViewManager viewManager;
    private final ArrayList<FrameInfo> frames = new ArrayList<>();
    private final Runnable onViewManagerAvailable = new Runnable() {
        @Override
        public void run() {
            processNextFrame();
        }
    };

    public FrameManager(ViewManager viewManager) {
        viewManager.addOnAvailableCallback(onViewManagerAvailable);
        this.viewManager = viewManager;
    }

    static class PlatformViewInfo {
        private final int viewId;
        private int width;
        private int height;
        private int top;
        private int left;
        private Bitmap bitmap = null;
        private boolean haveOverlay;
        private ImageProducer.ReleaseImageCallback releaseCallback = null;

        private FlutterMutatorsStack mutatorsStack;

        public FlutterMutatorsStack getMutatorsStack() {
            return mutatorsStack;
        }

        public void setMutatorsStack(FlutterMutatorsStack mutatorsStack) {
            this.mutatorsStack = mutatorsStack;
        }

        public int getViewId() {
            return viewId;
        }

        public int getWidth() {
            return width;
        }

        public void setWidth(int width) {
            this.width = width;
        }

        public int getHeight() {
            return height;
        }

        public void setHeight(int height) {
            this.height = height;
        }

        public int getTop() {
            return top;
        }

        public void setTop(int top) {
            this.top = top;
        }

        public int getLeft() {
            return left;
        }

        public void setLeft(int left) {
            this.left = left;
        }

        public Bitmap getBitmap() {
            return bitmap;
        }

        public void setBitmap(Bitmap bitmap) {
            this.bitmap = bitmap;
        }

        public boolean isHaveOverlay() {
            return haveOverlay;
        }

        public void setHaveOverlay(boolean haveOverlay) {
            this.haveOverlay = haveOverlay;
        }

        public void setReleaseCallback(ImageProducer.ReleaseImageCallback releaseCallback) {
            this.releaseCallback = releaseCallback;
        }

        boolean isReady() {
            return bitmap != null || !haveOverlay;
        }

        void release() {
            if (releaseCallback != null) {
                releaseCallback.releaseImage();
            }
        }

        public PlatformViewInfo(int viewId) {
            this.viewId = viewId;
            if (viewId == -1) {
                this.width = FrameLayout.LayoutParams.MATCH_PARENT;
                this.height = FrameLayout.LayoutParams.MATCH_PARENT;
                this.top = 0;
                this.left = 0;
                this.haveOverlay = true;
            } else {
                this.width = 0;
                this.height = 0;
                this.top = 0;
                this.left = 0;
                this.haveOverlay = true;
            }
        }
    }

    static class FrameInfo {
        final long rasterStart;
        final PlatformViewInfo[] views;

        public FrameInfo(long rasterStart, PlatformViewInfo[] views) {
            this.rasterStart = rasterStart;
            this.views = views;
        }

        public void release() {
            for (PlatformViewInfo view : views) {
                view.release();
            }
        }

        public boolean isReady() {
            for (PlatformViewInfo view : views) {
                if (!view.isReady()) {
                    return false;
                }
            }
            return true;
        }

        public void setPlatformViewInfo(int viewId, int width, int height, int top, int left, boolean haveOverlay, FlutterMutatorsStack mutatorsStack) {
            for (PlatformViewInfo view : views) {
                if (view.getViewId() == viewId) {
                    view.setWidth(width);
                    view.setHeight(height);
                    view.setTop(top);
                    view.setLeft(left);
                    view.setHaveOverlay(haveOverlay);
                    view.setMutatorsStack(mutatorsStack);
                    break;
                }
            }
        }

        public void onBitmapCreated(int viewId, Bitmap bitmap, ImageProducer.ReleaseImageCallback releaseCallback) {
            for (PlatformViewInfo view : views) {
                if (view.getViewId() == viewId) {
                    view.setBitmap(bitmap);
                    view.setReleaseCallback(releaseCallback);
                    break;
                }
            }
        }
    }

    synchronized void addNewFrameInfo(long rasterStart, int[] viewIds) {
        if (viewIds.length == 0) {
            if (!frames.isEmpty() && frames.get(frames.size() - 1).views.length == 0) {
                // duplicate empty frame
                return;
            }
        }
        PlatformViewInfo[] views = new PlatformViewInfo[viewIds.length];
        for (int i = 0; i < viewIds.length; i++) {
            views[i] = new PlatformViewInfo(viewIds[i]);
        }
        frames.add(new FrameInfo(rasterStart, views));
        if (viewIds.length == 0) {
            Handler handler = new Handler(Looper.getMainLooper());
            handler.post(new Runnable() {
                @Override
                public void run() {
                    processNextFrame();
                }
            });
        }
    }

    synchronized void updateFrameInfo(int viewId, long rasterStart, int width, int height, int top, int left, boolean haveOverlay, FlutterMutatorsStack mutatorsStack) {
        FrameInfo frame = null;
        for (FrameInfo item : frames) {
            if (item.rasterStart == rasterStart) {
                frame = item;
                break;
            }
        }
        if (frame != null) {
            Log.e("Flutter", "setPlatformViewInfo " + mutatorsStack);
            frame.setPlatformViewInfo(viewId, width, height, top, left, haveOverlay, mutatorsStack);
        } else {
            Log.e("Flutter", "setPlatformViewInfo not found frame");
        }
    }

    synchronized void onBitmapCreated(int viewId, long timestamp, Bitmap bitmap, ImageProducer.ReleaseImageCallback releaseCallback) {
        FrameInfo frame = null;
        for (FrameInfo item : frames) {
            if (item.rasterStart <= timestamp) {
                frame = item;
            } else {
                break;
            }
        }
        Log.e("Flutter", "onBitmapCreated frame " + frame);
        if (frame == null) {
            releaseCallback.releaseImage();
        } else {
            frame.onBitmapCreated(viewId, bitmap, releaseCallback);
            processNextFrame();
        }
    }

    synchronized void processNextFrame() {
        FrameInfo frame = null;
        int index = -1;
        for (FrameInfo item : frames) {
            index++;
            if (item.isReady()) {
                frame = item;
                break;
            }
        }
        Log.e("Flutter", "processNextFrame frame " + frame);
        if (frame != null) {
            if (viewManager.available()) {
                if (index > 0) {
                    Log.e("Flutter", "Invalid frame detected");
                    frames.subList(0, index).clear();
                }
                frames.remove(frame);
                viewManager.processNextFrame(frame);
            }
        }
        if (!frames.isEmpty()) {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                @Override
                public void doFrame(long l) {
                    processNextFrame();
                }
            });
        }
    }

    synchronized public void release() {
        viewManager.removeOnAvailableCallback(onViewManagerAvailable);;
        for (FrameInfo frame : frames) {
            frame.release();
        }
        frames.clear();
    }
}

class ImageReaderDataFlutter {
    Surface surface;
    int surfaceWidth;
    int surfaceHeight;
    int width;
    int height;

    int left;
    int top;
    int id;

    public ImageReaderDataFlutter(int id, Surface surface, int width, int height, int left, int top, int surfaceWidth, int surfaceHeight) {
        this.surface = surface;
        this.width = width;
        this.height = height;
        this.left = left;
        this.top = top;
        this.id = id;
    }
}


class ImageReaderData {

    ArrayList<ImageReaderItem> imageReaders = new ArrayList<>();

    public static class ImageReaderItem {
        ImageReader imageReader;
        int maxImageCount = 0;
        ArrayList<Image> openImages = new ArrayList<>();
        final int imageReaderId;

        public ImageReaderItem(int imageReaderId) {
            this.imageReaderId = imageReaderId;
        }

        void addImage(Image image) {
            openImages.add(image);
            if (openImages.size() > maxImageCount) {
                maxImageCount = openImages.size();
            }
        }

        void closeImage(Image image) {
            if (maxImageCount >= ImageProducer.KEEP_OPEN_IMAGES || openImages.size() >= ImageProducer.MAX_IMAGES - 1) {
                boolean removed = openImages.remove(image);
                if (removed) {
                    image.close();
                }
            } else {
                closeImageAfterDelay(image);
            }
        }

        void closeImageIfNeeded() {
            if (openImages.size() >= ImageProducer.MAX_IMAGES - 1) {
                closeImage(openImages.get(0));
            }
        }

        private void closeImageAfterDelay(Image image) {
            Choreographer.getInstance().postFrameCallback(new Choreographer.FrameCallback() {
                int count = 0;
                @Override
                public void doFrame(long l) {
                    count++;
                    if (count >= 3) {
                        boolean removed = openImages.remove(image);
                        if (removed) {
                            image.close();
                        }
                    } else {
                        Choreographer.getInstance().postFrameCallback(this);
                    }
                }
            });
        }
    }

    ImageReaderItem getImageReaderFor(int width, int height) {
        if (imageReaders.isEmpty()) {
            return null;
        }
        ImageReaderItem data = imageReaders.get(imageReaders.size() - 1);
        if (data.imageReader.getWidth() == width && data.imageReader.getHeight() == height) {
            return data;
        }
        return null;
    }

    synchronized void addImageReader(ImageReaderItem item) {
        imageReaders.add(item);
    }

    boolean isImageReaderClosed(ImageReader imageReader) {
        for (ImageReaderItem item : imageReaders) {
            if (item.imageReader == imageReader) {
                return false;
            }
        }
        return true;
    }

    synchronized void closeAllImageReader() {
        for (ImageReaderItem item : imageReaders) {
            item.imageReader.close();
        }
        imageReaders.clear();
    }
}
