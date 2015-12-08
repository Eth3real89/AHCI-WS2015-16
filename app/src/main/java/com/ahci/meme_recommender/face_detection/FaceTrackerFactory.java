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
package com.ahci.meme_recommender.face_detection;

/**
 * Created by jurgenhahn on 27/10/15.
 */

import android.content.Context;

import com.google.android.gms.vision.MultiProcessor;
import com.google.android.gms.vision.Tracker;
import com.google.android.gms.vision.face.Face;

/**
 * Factory for creating a face tracker to be associated with a new face.  The multiprocessor
 * uses this factory to create face trackers as needed -- one for each individual.
 */
public class FaceTrackerFactory implements MultiProcessor.Factory<Face> {

    private Overlay overlay;
    private Context context;
    private OnNewTrackerListener listener;

    public FaceTrackerFactory(Overlay overlay, Context context, OnNewTrackerListener listener) {
        this.overlay = overlay;
        this.context = context;
        this.listener = listener;
    }

    @Override
    public Tracker<Face> create(Face face) {
        FaceTracker tracker = new FaceTracker(overlay, context);
        listener.newFacetrackerCreated(tracker);
        return new FaceTracker(overlay, context);
    }

    public interface OnNewTrackerListener {
        public void newFacetrackerCreated(FaceTracker faceTracker);
    }
}