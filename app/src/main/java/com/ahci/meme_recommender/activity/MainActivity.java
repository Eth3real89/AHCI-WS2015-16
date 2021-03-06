/*
 * Copyright (C) The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ahci.meme_recommender.activity;

import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.ahci.meme_recommender.R;
import com.ahci.meme_recommender.activity.help.HelpDialogHelper;
import com.ahci.meme_recommender.activity.tutorial.TutorialHelper;
import com.ahci.meme_recommender.face_detection.FaceTracker;
import com.ahci.meme_recommender.face_detection.FaceTrackerFactory;
import com.ahci.meme_recommender.face_detection.user_face_watcher.FaceWatcherView;
import com.ahci.meme_recommender.json_parser.JSONParser;
import com.ahci.meme_recommender.model.Meme;
import com.ahci.meme_recommender.model.MemeList;
import com.ahci.meme_recommender.model.Rating;
import com.ahci.meme_recommender.model.Storage;
import com.ahci.meme_recommender.server_connection.ServerCorrespondence;
import com.ahci.meme_recommender.util.MemeWebViewWrapper;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;

public class MainActivity extends AppCompatActivity implements FaceTrackerFactory.OnNewTrackerListener {
    /** Only value that works right now is 1... */
    private static final int SITES_LOADED_AT_ONCE = 1;
    private static final String TAG = "FaceTracker";

    private FaceTracker faceTracker;

    private RelativeLayout nextButton;
    private RelativeLayout prevButton;

    private EmoticonIconView emoticonIconView;
    private SwipeAnimationView swipeAnimationView;

    private MemeWebViewWrapper memeWebViewWrapper;

    private TutorialHelper tutorialHelper;

    private String userId;
    private ServerCorrespondence.ServerResponseHandler onMemeDownloadListener;
    private ServerCorrespondence.ServerResponseHandler onFirstMemesDownloadListener;

    private ServerCorrespondence.ServerErrorHandler networkErrorHandler;

    private CameraSourceHelper cameraSourceHelper;
    private FaceWatcherView faceWatcherView;

    private MemeList memeList;
    private int memeListIndex;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.activity_main);
        // needs to happen before setup!!
        setupNetworkErrorHelper();
        maybeChangeServerId();
        memeListIndex = 0;

        if(firstAppStart()) {
            showTutorial();
        } else {
            userId = getId();
            setup();
            if(!userId.equals("-1")) {
                loadFirstMemes();
            }
        }
    }

    private void maybeChangeServerId() {
        String root = Environment.getExternalStorageDirectory().toString();
        File idFile = new File(root + "/meme_recommender_server.txt");
        if(idFile.exists() && idFile.isFile()) {
           Log.v("ahci_meme_recommender", "retrieving id stored in server file");
            try {
                BufferedReader reader = new BufferedReader(new FileReader(idFile));
                String s = reader.readLine();
                if(s != null) {
                    ServerCorrespondence.SERVER = s;
                }
                reader.close();
            } catch (java.io.IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void loadFirstMemes() {
        ServerCorrespondence.getMemeImages(userId, SITES_LOADED_AT_ONCE + 1, Rating.loadRatingsToSendToServer(new Storage(this)),
                this, onFirstMemesDownloadListener, networkErrorHandler);
    }

    private void loadNextMeme() {
        ServerCorrespondence.getMemeImages(userId, SITES_LOADED_AT_ONCE, Rating.loadRatingsToSendToServer(new Storage(this)),
                this, onMemeDownloadListener, networkErrorHandler);
    }

    private void setup() {
        memeList = new MemeList();

        referenceViews();
        setupControlButtons();
        cameraSourceHelper.checkCamera();
        setupMemeWebView();
        setupOnMemeDownloadListener();

    }

    private void setupControlButtons() {
        setupNextMemeButton(nextButton);
        setupPrevMemeButton(prevButton);

    }

    private void setupMemeWebView() {
        memeWebViewWrapper = new MemeWebViewWrapper(this,
                (RelativeLayout) findViewById(R.id.webview_wrapper));
    }

    /**
     * @return
     * <ul>
     *     <li><em>True</em> if this is the first start of the app</li>
     *     <li><em>False</em> if this is not the first start of the app</li>
     * </ul>
     * (technically: if the user finished the tutorial, in both cases)
     */
    private boolean firstAppStart() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        return prefs.getBoolean("finished_tutorial", true);
    }

    private String getId() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if(!prefs.contains("user_id")) {
            getIdFromServer();
            return "-1";
        } else {
            return prefs.getString("user_id", "-1");
        }
    }

    private void showTutorial() {
        tutorialHelper = new TutorialHelper(this, findViewById(R.id.tutorial_view_wrapper), new TutorialHelper.OnFinishListener() {
            @Override
            public void onTutorialFinish() {
                userId = getId();
                tutorialHelper.hide();
                setup();
                if(!userId.equals("-1")) {
                    loadFirstMemes();
                }
                cameraSourceHelper.startCameraSource();
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                SharedPreferences.Editor editor = prefs.edit();

                invalidateOptionsMenu();

                editor.putBoolean("finished_tutorial", false);
                editor.apply();
            }
        });
        tutorialHelper.showTutorial(getSupportFragmentManager(), getWindowManager());
    }

    private void setupOnMemeDownloadListener() {
        this.onMemeDownloadListener = new ServerCorrespondence.ServerResponseHandler() {
            @Override
            public void handleResponse(String response) {
                try {
                    Meme[] memes = JSONParser.loadMemes(response);
                    for(Meme m : memes) memeList.add(m);
                } catch (JSONException je) {
                    je.printStackTrace();
                }

                markRatingsAsSentToServer(response);
            }
        };


        this.onFirstMemesDownloadListener = new ServerCorrespondence.ServerResponseHandler() {
            @Override
            public void handleResponse(String response) {
                try {
                    Meme[] memes = JSONParser.loadMemes(response);
                    for(Meme m : memes) memeList.add(m);
                    if(memes.length > 1) {
                        memeWebViewWrapper.loadUrlInFront(memeList.getAtIndex(memeListIndex).getUrl());
                        memeWebViewWrapper.loadUrlInBackground(memeList.getAtIndex(memeListIndex + 1).getUrl());
                    }

                    markRatingsAsSentToServer(response);
                } catch (JSONException e) {
                    Log.d("ahci_json_error", "Error in string: " + response);
                    e.printStackTrace();
                }
            }
        };
    }

    private void markRatingsAsSentToServer(String response) {
        try {
            String[] ratedMemeIDs = JSONParser.loadRatedMemeIDs(response);
            Storage storage = new Storage(MainActivity.this);
            storage.openConnection(true);
            SQLiteDatabase db = storage.getDb();

            ContentValues values = new ContentValues();
            values.put(Rating.COLUMN_NAME_SENT_RATING_TO_SERVER, 1);
            for(String id : ratedMemeIDs) {
                db.update(Rating.TABLE_NAME, values, Rating.COLUMN_NAME_RATING_MEME_ID + "=\"" + id + "\"", null);
            }

            storage.closeConnection();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void setupNetworkErrorHelper() {
        this.networkErrorHandler = new NetworkErrorHelper(MainActivity.this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if(cameraSourceHelper != null) cameraSourceHelper.startCameraSource();
        FaceWatcherView.RUN_TIMER = true;
        EmoticonIconView.RUN_TIMER = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if(cameraSourceHelper != null) cameraSourceHelper.stopMPreview();
        FaceWatcherView.RUN_TIMER = false;
        EmoticonIconView.RUN_TIMER = false;
    }

    @Override
    protected void onDestroy() {
        FaceWatcherView.KILL_TIMER = true;
        EmoticonIconView.KILL_TIMER = true;
        super.onDestroy();
        if(cameraSourceHelper != null) cameraSourceHelper.release();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if(firstAppStart()) return true;

        new MenuInflater(this).inflate(R.menu.menu_main, menu);
        menu.findItem(R.id.action_explain).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                Toast.makeText(MainActivity.this, "Du hast noch nicht viele Memes gesehen, also werden zufällige Bilder angezeigt.", Toast.LENGTH_LONG).show();
                return true;
            }
        });

        menu.findItem(R.id.action_help).setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                new HelpDialogHelper().showHelpDialog(MainActivity.this, new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        if(faceTracker != null) faceTracker.reset();
                    }
                });
                return true;
            }
        });

        return true;
    }

    public void setupNextMemeButton(RelativeLayout nextMemeButton) {
        nextMemeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                FaceTracker.doNotTrack();
                int recognizedEmotion = FaceTracker.classify();
                storeReactionAndLoadNext(recognizedEmotion);

                if (faceTracker != null) {
                    faceTracker.reset();
                }

                showEmoticonAnimation(recognizedEmotion);
            }
        });
    }

    private void storeReactionAndLoadNext(int recognizedEmotion) {
        storeReaction(recognizedEmotion);
        loadNextMeme();
    }

    private void storeReaction(int recognizedEmotion) {
        Meme meme = memeList.getForUrl(memeWebViewWrapper.getCurrentMemeURL());
        if(meme != null) {
            Rating rating = new Rating();
            rating.setRatingValue(recognizedEmotion);
            rating.setMemeId(meme.getId());
            rating.setSentToServer(0);

            Storage storage = new Storage(this);
            storage.openConnection(true);
            storage.getDb().insert(Rating.TABLE_NAME, null, rating.toContentValues());
            storage.closeConnection();
        }
    }

    private void showEmoticonAnimation(int recognizedEmotion) {
        swipeAnimationView.showAnimation(recognizedEmotion, new SwipeAnimationView.AnimationStateListener() {
            @Override
            public void onAnimationFinish() {
                FaceTracker.doTrack();
                faceWatcherView.showMessagesIfNecessary();
                memeWebViewWrapper.showBackWebView();

                if(memeListIndex < memeList.getList().size()) {
                    memeListIndex++;
                    Meme next = memeList.getAtIndex(memeListIndex + 1);
                    if (next != null) {
                        memeWebViewWrapper.loadUrlInBackground(next.getUrl());
                    }
                }
            }

            @Override
            public void onAnimationStart() {
                faceWatcherView.hideMessagesIfNecessary();
            }
        });
    }

    /* @TODO */
    private void setupPrevMemeButton(RelativeLayout prevButton) {
        prevButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (memeListIndex <= 0) return;

                memeListIndex--;
                memeWebViewWrapper.showBackWebView();
                memeWebViewWrapper.loadUrlInFront(memeList.getAtIndex(memeListIndex).getUrl());
                faceTracker.reset();
            }
        });
    }

    /**
     * Callback for when a new face tracker is created.
     * Adds the FaceWatcherView (that informs the user when the device can't recognize
     * their facial expression or can't find their face) as an on update listener to the tracker.
     * @param faceTracker
     * The face tracker that was created.
     */
    @Override
    public void newFacetrackerCreated(FaceTracker faceTracker) {
        this.faceTracker = faceTracker;
        faceTracker.addOnUpdateListener(this.faceWatcherView);
    }

    /**
     * References all the activity's views.
     */
    private void referenceViews() {
        cameraSourceHelper = new CameraSourceHelper(this, this);
        cameraSourceHelper.referenceViews();

        nextButton = (RelativeLayout) this.findViewById(R.id.relative_layout_next);
        prevButton = (RelativeLayout) this.findViewById(R.id.relative_layout_previous);

        emoticonIconView = new EmoticonIconView((RelativeLayout) this.findViewById(R.id.relative_layout_emoticon), this, memeList);
        emoticonIconView.startUpdates();

        faceWatcherView = (FaceWatcherView) findViewById(R.id.face_watcher_view);

        swipeAnimationView = new SwipeAnimationView(
                (RelativeLayout) this.findViewById(R.id.emoticon_for_swipe_animation_wrapper),
                this);
    }

    private void getIdFromServer() {
        ServerCorrespondence.requestId(this, new ServerCorrespondence.ServerResponseHandler() {
            @Override
            public void handleResponse(String response) {
                JSONTokener tokener = new JSONTokener(response);
                try {
                    JSONObject obj = (JSONObject) tokener.nextValue();
                    if (obj.getString("status").equals("ok")) {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString("user_id", obj.getString("id"));
                        editor.apply();

                        userId = obj.getString("id");

                        // this has not happened at this point, so the "basic usage"
                        // is started now!
                        loadFirstMemes();

                    } else {
                        throw new Exception("Response: Status was not \"ok\".");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, networkErrorHandler);
    }

    /**
     * Either kills the app (if the permission was not granted)
     * or creates the camera source.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode != CameraSourceHelper.RC_HANDLE_CAMERA_PERM) {
            Log.d(TAG, "Got unexpected permission result: " + requestCode);
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            return;
        }

        cameraSourceHelper.onRequestPermissionResult(grantResults);
    }

}
