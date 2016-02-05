package com.fivehundredpx.android.blur;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.support.v8.renderscript.Allocation;
import android.support.v8.renderscript.Element;
import android.support.v8.renderscript.RenderScript;
import android.support.v8.renderscript.ScriptIntrinsicBlur;
import android.util.AttributeSet;
import android.view.View;

/**
 * A custom view for presenting a dynamically blurred version of another view's content.
 * <p/>
 * Use {@link #setBlurredView(android.view.View)} to set up the reference to the view to be blurred.
 * After that, call {@link #invalidate()} to trigger blurring whenever necessary.
 */
public class BlurringView extends View {

    private boolean canRenderScript = false;
    private int[] pos1 = {0,0}, pos2 = {0,0};

    public BlurringView(Context context) {
        this(context, null);
    }

    public BlurringView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final Resources res = getResources();
        final int defaultBlurRadius = res.getInteger(R.integer.default_blur_radius);
        final int defaultDownsampleFactor = res.getInteger(R.integer.default_downsample_factor);
        final int defaultOverlayColor = res.getColor(R.color.default_overlay_color);

        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.PxBlurringView);
        setOverlayColor(Color.argb(125, 0, 0, 0));

        if (isSupported()) {
            canRenderScript = initializeRenderScript(context);
        }
        if (canRenderScript) {
            setBlurRadius(a.getInt(R.styleable.PxBlurringView_blurRadius, defaultBlurRadius));
            setDownsampleFactor(a.getInt(R.styleable.PxBlurringView_downsampleFactor,
                    defaultDownsampleFactor));
            setOverlayColor(a.getColor(R.styleable.PxBlurringView_overlayColor, defaultOverlayColor));
        } else {
            setBackgroundColor(Color.TRANSPARENT);
        }
        a.recycle();
    }

    private boolean isSupported() {
        String abi;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            abi = Build.CPU_ABI;
        } else {
            abi = Build.SUPPORTED_ABIS[0];
        }
        return !abi.equals("armeabi");
    }

    public void setBlurredView(View blurredView) {
        mBlurredView = blurredView;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (isSupported() && canRenderScript) {
            if (mBlurredView != null) {
                if (prepare()) {
                    // If the background of the blurred view is a color drawable, we use it to clear
                    // the blurring canvas, which ensures that edges of the child views are blurred
                    // as well; otherwise we clear the blurring canvas with a transparent color.
                    if (mBlurredView.getBackground() != null && mBlurredView.getBackground() instanceof ColorDrawable) {
                        mBitmapToBlur.eraseColor(((ColorDrawable) mBlurredView.getBackground()).getColor());
                    } else {
                        mBitmapToBlur.eraseColor(Color.TRANSPARENT);
                    }

                    mBlurredView.draw(mBlurringCanvas);
                    blur();
                    canvas.save();
                    mBlurredView.getLocationOnScreen(pos1);
                    getLocationOnScreen(pos2);
                    canvas.translate(pos1[0] - pos2[0], pos1[1] - pos2[1]);
                    canvas.scale(mDownsampleFactor, mDownsampleFactor);
                    if (mBlurredView.getBackground() != null && mBlurredView.getBackground() instanceof LayerDrawable) {
                        ((LayerDrawable) mBlurredView.getBackground()).getDrawable(0).draw(canvas);
                    }
                    canvas.drawBitmap(mBlurredBitmap, 0, 0, null);
                    canvas.restore();
                }
                canvas.drawColor(mOverlayColor);
            }
        }
    }

    public void setBlurRadius(int radius) {
        if (mBlurScript != null && canRenderScript) {
            mBlurScript.setRadius(radius);
        }
    }

    public void setDownsampleFactor(int factor) {
        if (factor <= 0) {
            throw new IllegalArgumentException("Downsample factor must be greater than 0.");
        }

        if (mDownsampleFactor != factor) {
            mDownsampleFactor = factor;
            mDownsampleFactorChanged = true;
        }
    }

    public void setOverlayColor(int color) {
        mOverlayColor = color;
    }

    private boolean initializeRenderScript(Context context) {
        //Renderscript Crash: some users fail to create mBlurScript
        try {
            mRenderScript = RenderScript.create(context);
            mBlurScript = ScriptIntrinsicBlur.create(mRenderScript, Element.U8_4(mRenderScript));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    protected boolean prepare() {
        if (!isSupported() || !canRenderScript) {
            return false;
        } else {
            final int width = mBlurredView.getWidth();
            final int height = mBlurredView.getHeight();

            if (mBlurringCanvas == null || mDownsampleFactorChanged
                    || mBlurredViewWidth != width || mBlurredViewHeight != height) {
                mDownsampleFactorChanged = false;

                mBlurredViewWidth = width;
                mBlurredViewHeight = height;

                int scaledWidth = width / mDownsampleFactor;
                int scaledHeight = height / mDownsampleFactor;

                // The following manipulation is to avoid some RenderScript artifacts at the edge.
                scaledWidth = scaledWidth - scaledWidth % 4 + 4;
                scaledHeight = scaledHeight - scaledHeight % 4 + 4;

                if (mBlurredBitmap == null
                        || mBlurredBitmap.getWidth() != scaledWidth
                        || mBlurredBitmap.getHeight() != scaledHeight) {
                    mBitmapToBlur = Bitmap.createBitmap(scaledWidth, scaledHeight,
                            Bitmap.Config.ARGB_8888);
                    if (mBitmapToBlur == null) {
                        return false;
                    }

                    mBlurredBitmap = Bitmap.createBitmap(scaledWidth, scaledHeight,
                            Bitmap.Config.ARGB_8888);
                    if (mBlurredBitmap == null) {
                        return false;
                    }
                }

                mBlurringCanvas = new Canvas(mBitmapToBlur);
                mBlurringCanvas.scale(1f / mDownsampleFactor, 1f / mDownsampleFactor);
                mBlurInput = Allocation.createFromBitmap(mRenderScript, mBitmapToBlur,
                        Allocation.MipmapControl.MIPMAP_NONE, Allocation.USAGE_SCRIPT);
                mBlurOutput = Allocation.createTyped(mRenderScript, mBlurInput.getType());
            }
            return true;
        }
    }

    protected void blur() {
        if (mBlurScript != null) {
            mBlurInput.copyFrom(mBitmapToBlur);
            mBlurScript.setInput(mBlurInput);
            mBlurScript.forEach(mBlurOutput);
            mBlurOutput.copyTo(mBlurredBitmap);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mRenderScript != null){
            mRenderScript.destroy();
        }
    }

    private int mDownsampleFactor;
    private int mOverlayColor;

    private View mBlurredView;
    private int mBlurredViewWidth, mBlurredViewHeight;

    private boolean mDownsampleFactorChanged;
    private Bitmap mBitmapToBlur, mBlurredBitmap;
    private Canvas mBlurringCanvas;
    private RenderScript mRenderScript;
    private ScriptIntrinsicBlur mBlurScript;
    private Allocation mBlurInput, mBlurOutput;

}
