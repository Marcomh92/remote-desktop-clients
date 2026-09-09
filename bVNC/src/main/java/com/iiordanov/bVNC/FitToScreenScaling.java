/**
 * Copyright (C) 2012 Iordan Iordanov
 * Copyright (C) 2009 Michael A. MacDonald
 * <p>
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * <p>
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307,
 * USA.
 */

package com.iiordanov.bVNC;

import android.graphics.Matrix;
import android.widget.ImageView.ScaleType;

import com.undatech.remoteClientUi.R;

/**
 * @author Michael A. MacDonald
 */
class FitToScreenScaling extends AbstractScaling {

    static final String TAG = "FitToScreenScaling";
    int canvasXOffset;
    int canvasYOffset;
    float scaling;
    float minimumScale;
    private Matrix matrix;

    /**
     * @param id
     * @param scaleType
     */
    public FitToScreenScaling() {
        super(R.id.itemFitToScreen, ScaleType.FIT_CENTER);
        matrix = new Matrix();
        scaling = 0;
    }

    /* (non-Javadoc)
     * @see com.iiordanov.bVNC.AbstractScaling#getDefaultHandlerId()
     */
    @Override
    int getDefaultHandlerId() {
        return R.id.itemInputTouchPanZoomMouse;
    }

    /* (non-Javadoc)
     * @see com.iiordanov.bVNC.AbstractScaling#isAbleToPan()
     */
    @Override
    public boolean isAbleToPan() {
        return false;
    }

    /* (non-Javadoc)
     * @see com.iiordanov.bVNC.AbstractScaling#isValidInputMode(int)
     */
    @Override
    boolean isValidInputMode(int mode) {
        return true;
    }

    /**
     * Call after scaling and matrix have been changed to resolve scrolling
     * @param activity
     */
    private void resolveZoom(RemoteCanvasActivity activity) {
        activity.getCanvas().resetScroll();
        //activity.getCanvas().absolutePan(activity.getCanvas().absoluteXPosition,0);
    }

    /* (non-Javadoc)
     * @see com.iiordanov.bVNC.AbstractScaling#getScale()
     */
    @Override
    public float getZoomFactor() {
        return scaling;
    }

    /* (non-Javadoc)
     * @see com.iiordanov.bVNC.AbstractScaling#adjust(com.iiordanov.bVNC.RemoteCanvasActivity, float, float, float)
     */
    @Override
    public void changeZoom(RemoteCanvasActivity activity, float scaleFactor, float fx, float fy) {

        float oldScale;
        float newScale = scaleFactor * scaling;
        float floor = minimumScale;
        if (scaleFactor < 1) {
            if (newScale < floor) {
                newScale = floor;
            }
        } else {
            if (newScale > 4) {
                newScale = 4;
            }
        }

        RemoteCanvas canvas = activity.getCanvas();
        // ax is the absolute x of the focus
        int xPan = canvas.absoluteXPosition;
        float ax = (fx / scaling) + xPan;
        float newXPan = (scaling * xPan - scaling * ax + newScale * ax) / newScale;
        int yPan = canvas.absoluteYPosition;
        float ay = (fy / scaling) + yPan;
        float newYPan = (scaling * yPan - scaling * ay + newScale * ay) / newScale;

        // Here we do snapping to 1:1. If we are approaching scale = 1, we snap to it.
        oldScale = scaling;
        if ((newScale > 0.95f && newScale < 1.00f) ||
                (newScale > 1.00f && newScale < 1.05f)) {
            newScale = 1.f;
        }

        resetMatrix();
        scaling = newScale;
        matrix.postScale(scaling, scaling);
        canvas.setImageMatrix(matrix);
        resolveZoom(activity);

        // Only if we have actually scaled do we pan and potentially set mouse position.
        // Use absolutePan (not relativePan) so the canvasZoomer.isAbleToPan() gate
        // in RemoteCanvas.relativePan — which is hard-coded false for this scaling
        // mode — does not silently drop the focal-anchored pan delta. absolutePan
        // sets absoluteXPosition/absoluteYPosition directly with edge clamping and
        // triggers the same resetScroll() redraw path.
        if (oldScale != newScale) {
            canvas.absolutePan((int) newXPan, (int) newYPan);
        }
    }

    private void resetMatrix() {
        matrix.reset();
        matrix.preTranslate(canvasXOffset, canvasYOffset);
    }

    /* (non-Javadoc)
     * @see com.iiordanov.bVNC.AbstractScaling#setScaleTypeForActivity(com.iiordanov.bVNC.RemoteCanvasActivity)
     */
    @Override
    void setScaleTypeForActivity(RemoteCanvasActivity activity) {
        super.setScaleTypeForActivity(activity);
        RemoteCanvas canvas = activity.getCanvas();
        if (canvas == null || canvas.myDrawable == null)
            return;
        canvasXOffset = -canvas.getCenteredXOffset();
        canvasYOffset = -canvas.getCenteredYOffset();
        canvas.computeShiftFromFullToView();
        minimumScale = canvas.myDrawable.getMinimumScale();
        scaling = minimumScale;
        resetMatrix();
        matrix.postScale(scaling, scaling);
        canvas.setImageMatrix(matrix);

        canvas.absoluteXPosition = 0;
        canvas.absoluteYPosition = 0;
        if (!canvas.myDrawable.widthRatioLessThanHeightRatio()) {
            canvas.absoluteXPosition = -(int) (((canvas.getWidth() - canvas.getImageWidth() * minimumScale) / 2) / minimumScale);
        } else {
            canvas.absoluteYPosition = -(int) (((canvas.getHeight() - canvas.getImageHeight() * minimumScale) / 2) / minimumScale);
        }
        resolveZoom(activity);
        canvas.relativePan(0, 0);
    }
}
