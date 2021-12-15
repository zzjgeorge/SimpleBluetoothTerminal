package de.kai_morich.simple_bluetooth_terminal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Created by wow on 3/23/18.
 */

public class ScreenCapturer {
    private static MediaProjection sMediaProjection;
    boolean isScreenCaptureStarted;
    OnImageCaptureScreenListener listener;
    private int mDensity;
    private Display mDisplay;
    private int mWidth;
    private int mHeight;
    private ImageReader mImageReader;
    private VirtualDisplay mVirtualDisplay;
    private Handler mHandler;
    private String STORE_DIR;
    private Context mContext;

    public Bitmap screenshotBitmap;

    public ScreenCapturer(Context context, MediaProjection mediaProjection, String savePath) {
        sMediaProjection = mediaProjection;
        mContext = context;

        isScreenCaptureStarted = false;

        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mHandler = new Handler();
                Looper.loop();
            }
        }.start();
    }

    public ScreenCapturer startProjection() {

        WindowManager window = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
        mDisplay = window.getDefaultDisplay();
        final DisplayMetrics metrics = new DisplayMetrics();
        // use getMetrics is 2030, use getRealMetrics is 2160, the diff is NavigationBar's height
        mDisplay.getRealMetrics(metrics);
        mDensity = metrics.densityDpi;
        Toast.makeText(mContext, "metrics.widthPixels is " + metrics.widthPixels, Toast.LENGTH_SHORT);
        Toast.makeText(mContext, "metrics.heightPixels is " + metrics.heightPixels, Toast.LENGTH_SHORT);
        mWidth = metrics.widthPixels;//size.x;
        mHeight = metrics.heightPixels;//size.y;
        isScreenCaptureStarted = true;
        //start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 2);
        mVirtualDisplay = sMediaProjection.createVirtualDisplay(
                "ScreenShot",
                mWidth,
                mHeight,
                mDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                mImageReader.getSurface(),
                null,
                mHandler);
        return this;
    }

    public void takeScreenshot() {
        Image image = null;
        // Bitmap bitmap = null;
        image = mImageReader.acquireLatestImage();
        if (image != null) {
            screenshotBitmap = ImageUtils.image_2_bitmap(image, Bitmap.Config.ARGB_8888);
        }
        image.close();
        //stopProjection();
    }

    public ScreenCapturer stopProjection() {
        isScreenCaptureStarted = false;
        Log.d("WOW", "Screen captured");
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (sMediaProjection != null) {
                    sMediaProjection.stop();
                }
            }
        });
        return this;
    }

    public ScreenCapturer setListener(OnImageCaptureScreenListener listener) {
        this.listener = listener;
        return this;
    }

    public interface OnImageCaptureScreenListener {
        public void imageCaptured(byte[] image);
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mVirtualDisplay != null) {
                        mVirtualDisplay.release();
                    }
                    if (mImageReader != null) {
                        mImageReader.setOnImageAvailableListener(null, null);
                    }
                    sMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                }
            });
        }
    }
}