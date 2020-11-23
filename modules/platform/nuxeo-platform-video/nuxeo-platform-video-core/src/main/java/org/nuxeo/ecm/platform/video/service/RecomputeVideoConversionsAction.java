/*
 * (C) Copyright 2020 Nuxeo (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Charles Boidot
 */
package org.nuxeo.ecm.platform.video.service;

import static org.nuxeo.ecm.core.api.CoreSession.ALLOW_VERSION_WRITE;
import static org.nuxeo.ecm.core.api.versioning.VersioningService.DISABLE_AUTO_CHECKOUT;
import static org.nuxeo.ecm.core.bulk.BulkServiceImpl.STATUS_STREAM;
import static org.nuxeo.ecm.core.bulk.action.SetPropertiesAction.PARAM_DISABLE_AUDIT;
import static org.nuxeo.ecm.platform.video.VideoConstants.TRANSCODED_VIDEOS_PROPERTY;
import static org.nuxeo.lib.stream.computation.AbstractComputation.INPUT_1;
import static org.nuxeo.lib.stream.computation.AbstractComputation.OUTPUT_1;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentNotFoundException;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.bulk.action.computation.AbstractBulkComputation;
import org.nuxeo.ecm.core.bulk.message.BulkCommand;
import org.nuxeo.ecm.core.convert.api.ConversionException;
import org.nuxeo.ecm.platform.video.TranscodedVideo;
import org.nuxeo.ecm.platform.video.Video;
import org.nuxeo.ecm.platform.video.VideoConstants;
import org.nuxeo.ecm.platform.video.VideoDocument;
import org.nuxeo.lib.stream.computation.Topology;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.stream.StreamProcessorTopology;
import org.nuxeo.runtime.transaction.TransactionHelper;

/**
 * BAF Computation that fills video renditions for the blob property described by the given xpath.
 *
 * @since 11.5
 */
public class RecomputeVideoConversionsAction implements StreamProcessorTopology {

    private static final Logger log = LogManager.getLogger(RecomputeVideoConversionsAction.class);

    public static final String ACTION_NAME = "recomputeVideoConversion";

    // @since 11.4
    public static final String ACTION_FULL_NAME = "bulk/" + ACTION_NAME;

    public static final String PARAM_XPATH = "xpath";

    public static final String PARAM_CONVERSION_NAME = "conversionName";

    @Override
    public Topology getTopology(Map<String, String> options) {
        return Topology.builder()
                       .addComputation(RecomputeRenditionsComputation::new, //
                               Arrays.asList(INPUT_1 + ":" + ACTION_FULL_NAME, OUTPUT_1 + ":" + STATUS_STREAM))
                       .build();
    }

    public static class RecomputeRenditionsComputation extends AbstractBulkComputation {

        public static final String PICTURE_VIEWS_GENERATION_DONE_EVENT = "pictureViewsGenerationDone";

        protected String xpath;

        protected List<String> conversionNames;

        public RecomputeRenditionsComputation() {
            super(ACTION_FULL_NAME);
        }

        @Override
        public void startBucket(String bucketKey) {
            BulkCommand command = getCurrentCommand();
            xpath = command.getParam(PARAM_XPATH);
            conversionNames = command.getParam(PARAM_CONVERSION_NAME);
        }

        @Override
        protected void compute(CoreSession session, List<String> ids, Map<String, Serializable> properties) {
            log.debug("Compute action: {} for doc ids: {}", ACTION_NAME, ids);
            for (String docId : ids) {
                if (!session.exists(new IdRef(docId))) {
                    log.debug("Doc id doesn't exist: {}", docId);
                    continue;
                }

                DocumentModel workingDocument = session.getDocument(new IdRef(docId));
                Property fileProp = workingDocument.getProperty(xpath);
                Blob blob = (Blob) fileProp.getValue();
                if (blob == null) {
                    // do nothing
                    log.debug("No blob for doc: {}", workingDocument);
                    continue;
                }

                var transcodedVideos = new ArrayList<Map<String, Serializable>>();

                try {
                    VideoDocument videoDoc = workingDocument.getAdapter(VideoDocument.class);
                    VideoService videoService = Framework.getService(VideoService.class);
                    Video video = videoDoc.getVideo();

                    List<String> conversionNamesArray = new ArrayList<>();

                    if (conversionNames.isEmpty()) {
                        // Recomputing all available renditions
                        for (VideoConversion conversion : videoService.getAvailableVideoConversions()) {
                            conversionNamesArray.add(conversion.getName());
                        }
                    } else {
                        // Recomputing wanted renditions
                        conversionNamesArray.addAll(conversionNames);

                    }
                    for (String conversion : conversionNamesArray) {
                        TransactionHelper.commitOrRollbackTransaction();
                        TranscodedVideo transcodedVideo = null;
                        try {
                            transcodedVideo = videoService.convert(video, conversion);
                        } catch (ConversionException e) {
                            log.warn("Conversion to {} has failed", conversion);
                        }
                        TransactionHelper.startTransaction();
                        saveRendition(workingDocument, conversion, transcodedVideo, session);
                    }
                } catch (DocumentNotFoundException e) {
                    // a parent of the document may have been deleted.
                    continue;
                }
                if (workingDocument.isVersion()) {
                    workingDocument.putContextData(ALLOW_VERSION_WRITE, Boolean.TRUE);
                }
                workingDocument.putContextData("disableNotificationService", Boolean.TRUE);
                workingDocument.putContextData(PARAM_DISABLE_AUDIT, Boolean.TRUE);
                workingDocument.putContextData(DISABLE_AUTO_CHECKOUT, Boolean.TRUE);
                session.saveDocument(workingDocument);
            }
        }
    }

    protected static void saveRendition(DocumentModel doc, String conversionName, TranscodedVideo transcodedVideo,
            CoreSession session) {
        List<Map<String, Serializable>> transcodedVideos = (List<Map<String, Serializable>>) doc.getPropertyValue(
                TRANSCODED_VIDEOS_PROPERTY);
        if (transcodedVideos == null) {
            transcodedVideos = new ArrayList<>();
        } else {
            if (transcodedVideo == null) {
                for (Map<String, Serializable> tv : transcodedVideos) {
                    if (tv.get("name") == conversionName) {
                        transcodedVideos.remove(tv);
                    }
                }
            } else {
                transcodedVideos = transcodedVideos.stream()
                                                   .filter(map -> !transcodedVideo.getName().equals(map.get("name")))
                                                   .collect(Collectors.toList());
            }

        }
        if (transcodedVideo != null) {
            transcodedVideos.add(transcodedVideo.toMap());
        }
        doc.setPropertyValue(TRANSCODED_VIDEOS_PROPERTY, (Serializable) transcodedVideos);
        if (doc.isVersion()) {
            doc.putContextData(ALLOW_VERSION_WRITE, Boolean.TRUE);
        }
        session.saveDocument(doc);
    }
}
