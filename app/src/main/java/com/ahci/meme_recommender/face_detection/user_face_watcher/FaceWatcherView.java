package com.ahci.meme_recommender.face_detection.user_face_watcher;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;
import android.os.Build;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ahci.meme_recommender.R;
import com.ahci.meme_recommender.face_detection.OnFaceUpdateListener;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.Landmark;

import java.util.ArrayList;
import java.util.List;

/**
 * This View is a mostly-invisible overlay that watches how users look at the camera
 * (via Face objects) and may print warnings / tells the users how to correct the angle
 * or position of their devices.
 */
public class FaceWatcherView extends RelativeLayout implements OnFaceUpdateListener {

    private static final boolean LOGGING = false;
    public static boolean RUN_TIMER = true;
    public static boolean KILL_TIMER = false;

    private static long timeLastFaceRetreived;

    public static int CAMERA_WIDTH;
    public static int CAMERA_HEIGHT;

    private WebView correctionView;

    private List<PointF> last5Positions;
    private Thread timerThread;
    private Timer timer;

    public static void startTimer() {
        if(!RUN_TIMER) {
            RUN_TIMER = true;
        }
    }

    public FaceWatcherView(Context context) {
        super(context);
        setup();
    }

    public FaceWatcherView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setup();
    }

    public FaceWatcherView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setup();
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public FaceWatcherView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setup();
    }

    private void setup() {
        setupCorrectionNotificationView();

        last5Positions = new ArrayList<>();

        timer = new Timer();
        timerThread = new Thread(timer);
        timerThread.start();

        enableDisableViewGroup(this, false);
    }

    private void setupCorrectionNotificationView() {
        correctionView = new WebView(this.getContext());

        LayoutParams params = new LayoutParams(100, 100);
        params.addRule(RelativeLayout.CENTER_HORIZONTAL, RelativeLayout.TRUE);
        params.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM, RelativeLayout.TRUE);
        params.setMargins(0, 0, 0, 50);
        correctionView.setBackgroundColor(Color.WHITE);
        correctionView.setLayoutParams(params);

        correctionView.setVisibility(View.INVISIBLE);

        this.addView(correctionView);
    }

    public void onFaceUpdate(Face face) {
        Log.d("ahci_debug", "onFaceUpdate");

        timeLastFaceRetreived = System.currentTimeMillis();

        last5Positions.add(0, face.getPosition());
        while (last5Positions.size() > 5) {
            last5Positions.remove(5);
        }

        if(face.getIsSmilingProbability() >= 0) {
            correctionView.setVisibility(View.INVISIBLE);

            invalidate();
            return;
        }

        Correction correction = new Correction();
        isPositionWrong(face, correction);
        isAngleWrong(face, correction);

        showCorrection(correction);
    }

    private void showCorrection(Correction correction) {
        if(correction.hasCorrection) {

            StringBuilder builder = new StringBuilder();

            if(correction.fixRotation == -1) {
                Log.d("ahci_debug", "Turn head to left!!");

                correctionView.loadUrl("file:///android_asset/turn_left_no_transp.gif");
                correctionView.setVisibility(View.VISIBLE);
            } else if(correction.fixRotation == 1) {
                Log.d("ahci_debug", "Turn head to right!!");

                correctionView.loadUrl("file:///android_asset/turn_right_no_transp.gif");
                correctionView.setVisibility(View.VISIBLE);
            }

            if(correction.fixXPos == -1) {
                Log.d("ahci_debug", "Move head to the left");
            } else if (correction.fixXPos == 1) {
                Log.d("ahci_debug", "Move head to the right");
            }

            if(correction.fixYPos == -1) {
                Log.d("ahci_debug", "Move head downwards");
            } else if(correction.fixYPos == 1) {
                Log.d("ahci_debug", "Move head upwards");
            }

        } else {
            correctionView.setVisibility(View.INVISIBLE);
        }
    }

    /*
    From: https://developers.google.com/vision/face-detection-concepts
    Euler Y angle	detectable landmarks
        < -36 degrees	left eye, left mouth, left ear, nose base, left cheek
        -36 degrees to -12 degrees	left mouth, nose base, bottom mouth, right eye, left eye, left cheek, left ear tip
        -12 degrees to 12 degrees	right eye, left eye, nose base, left cheek, right cheek, left mouth, right mouth, bottom mouth
        12 degrees to 36 degrees	right mouth, nose base, bottom mouth, left eye, right eye, right cheek, right ear tip
        > 36 degrees	right eye, right mouth, right ear, nose base, right cheek
     */
    private void isAngleWrong(Face face, Correction correction) {
        int angleClass = getAngleClass(face);
        Log.d("ahci_debug", "Angle class: " + angleClass);
        switch (angleClass) {
            case -1:
                Log.d("ahci_debug", "No landmarks detected");
                break;
            case 0:
            case 1:
                correction.hasCorrection = true;
                correction.fixRotation = -1;
                break;
            case 2: // good case (no correction necessary)
                break;
            case 3:
            case 4:
                correction.hasCorrection = true;
                correction.fixRotation = 1;
                break;
        }
    }

    private int getAngleClass(Face face) {
        List<Landmark> landmarkList = face.getLandmarks();

        boolean hasLeftEye = false, hasLeftCheek = false, hasRightEye = false, hasRightCheek = false,
                hasBottomMouth = false;

        for(int i = 0; i < landmarkList.size(); i++) {
            int type = landmarkList.get(i).getType();
            if (type == Landmark.LEFT_EYE) hasLeftEye = true;
            else if (type == Landmark.RIGHT_EYE) hasRightEye = true;
            else if (type == Landmark.BOTTOM_MOUTH) hasBottomMouth = true;
            else if (type == Landmark.LEFT_CHEEK) hasLeftCheek = true;
            else if (type == Landmark.RIGHT_CHEEK) hasRightCheek = true;
        }

        if(!(hasLeftEye || hasLeftCheek || hasRightCheek ||hasRightEye ||hasBottomMouth)) return -1;

        if(hasLeftCheek) {
            if(hasBottomMouth) {
                if(hasRightCheek) return 2;
                else return 1;
            } else {
                if(hasLeftEye) return 0;
                return -1;
            }
        } else {
            if(hasBottomMouth) return 3;
            if(hasRightEye) return 4;
            return -1;
        }
    }

    private void isPositionWrong(Face face, Correction correction) {
        PointF facePos = face.getPosition();

        Log.d("ahci_face_pos", face.getPosition().x + "\t" + face.getPosition().y);

        if (facePos.x <= -1.0 * CAMERA_WIDTH / 6.0) {
            correction.hasCorrection = true;
            correction.fixXPos = -1;
        } else if(facePos.x >= CAMERA_WIDTH / 6.0) {
            correction.hasCorrection = true;
            correction.fixXPos = 1;
        }

        if (facePos.y <= -1.0 * CAMERA_HEIGHT / 6.0) {
            correction.hasCorrection = true;
            correction.fixYPos = -1;
        } else if (facePos.y >= CAMERA_HEIGHT / 6.0) {
            correction.hasCorrection = true;
            correction.fixYPos = 1;
        }
    }

    /**
     * Enables/Disables all child views in a view group.
     *
     * @param viewGroup the view group
     * @param enabled <code>true</code> to enable, <code>false</code> to disable
     * the views.
     */
    public static void enableDisableViewGroup(ViewGroup viewGroup, boolean enabled) {
        int childCount = viewGroup.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View view = viewGroup.getChildAt(i);
            view.setEnabled(enabled);
            if (view instanceof ViewGroup) {
                enableDisableViewGroup((ViewGroup) view, enabled);
            }
        }
    }

    private static synchronized long getTimeLastFaceRetreived() {
        return timeLastFaceRetreived;
    }


    private class Correction {
        private boolean hasCorrection = false;
        /**
         * Possible values:
         * <ul>
         *     <li>-1 = turn to right</li>
         *     <li>0 = don't turn face</li>
         *     <li>1 = turn to left</li>
         * </ul>
         */
        private int fixRotation = 0;
        private int fixXPos = 0;
        private int fixYPos = 0;
    }

    private static class Log {

        static void d(String tag, String message) {
            if(LOGGING) {
                android.util.Log.d(tag, message);
            }
        }
    }

    private class Timer implements Runnable{

        @Override
        public void run() {
            while(!KILL_TIMER) {
                if (RUN_TIMER) {
                    long currentTime = System.currentTimeMillis();

                    if (currentTime >= timeLastFaceRetreived + 500l) {
                        FaceWatcherView.Log.d("ahci_debug", "no face found in the last 0.5 seconds");
                    }

                    try {
                        Thread.sleep(50l);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    try {
                        Thread.sleep(1000l);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

}
