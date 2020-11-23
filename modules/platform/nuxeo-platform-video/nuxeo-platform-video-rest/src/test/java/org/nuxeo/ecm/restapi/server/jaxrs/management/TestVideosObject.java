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

package org.nuxeo.ecm.restapi.server.jaxrs.management;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static javax.servlet.http.HttpServletResponse.SC_OK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.nuxeo.ecm.core.bulk.io.BulkConstants.STATUS_ERROR_COUNT;
import static org.nuxeo.ecm.core.bulk.io.BulkConstants.STATUS_ERROR_MESSAGE;
import static org.nuxeo.ecm.core.bulk.io.BulkConstants.STATUS_HAS_ERROR;
import static org.nuxeo.ecm.core.bulk.io.BulkConstants.STATUS_PROCESSED;
import static org.nuxeo.ecm.core.bulk.io.BulkConstants.STATUS_TOTAL;
import static org.nuxeo.ecm.platform.video.VideoConstants.TRANSCODED_VIDEOS_PROPERTY;

import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.ws.rs.core.MultivaluedMap;

import org.junit.Before;
import org.junit.Test;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.platform.video.VideoFeature;
import org.nuxeo.ecm.restapi.test.ManagementBaseTest;
import org.nuxeo.jaxrs.test.CloseableClientResponse;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.jersey.core.util.MultivaluedMapImpl;

/**
 * @since 11.4
 */
@Features(VideoFeature.class)
@Deploy("org.nuxeo.ecm.platform.video.rest")
public class TestVideosObject extends ManagementBaseTest {

    @Inject
    protected CoreSession session;

    protected DocumentRef docRef;

    @Before
    public void createDocument() throws IOException {

        DocumentModel doc = session.createDocumentModel("/", "videoDoc", "Video");
        Blob blob = Blobs.createBlob(FileUtils.getResourceFileFromContext("videos/video.mpg"), "video/mpg",
                StandardCharsets.UTF_8.name(), "video.mpg");
        doc.setPropertyValue("file:content", (Serializable) blob);
        doc = session.createDocument(doc);
        txFeature.nextTransaction();
        doc = session.getDocument(doc.getRef());
        docRef = doc.getRef();
    }

    @Test
    public void testRecomputeVideosNoQueryNoConversions() throws IOException {
        doTestRecomputeVideos(null, false, true, null);
    }

    @Test
    public void testRecomputeVideosValidQueryCustomConversion() throws IOException {
        String query = "SELECT * FROM Document WHERE ecm:mixinType = 'Video'";
        doTestRecomputeVideos(query, false, true, List.of("WebM 480p"));
    }

    @Test
    public void testRecomputeVideosImpossibleConversion() throws IOException {
        doTestRecomputeVideos(null, true, false, List.of("foo 480p"));
    }

    @Test
    public void testRecomputeVideosCustomRenditionsList() throws IOException {
        doTestRecomputeVideos(null, false, true, List.of("WebM 480p", "MP4 480p"));
    }

    @Test
    public void testRecomputeVideosInvalidQuery() throws IOException {
        String query = "SELECT * FROM nowhere";
        doTestRecomputeVideos(query, false, false, null);
    }

    protected void doTestRecomputeVideos(String query, boolean impossibleConversionError, boolean success,
            List<String> expectedRenditions) throws IOException {

        // Test there is no already generated renditions
        DocumentModel doc = session.getDocument(docRef);
        List<Map<String, Serializable>> transcodedVideos = (List<Map<String, Serializable>>) doc.getPropertyValue(
                TRANSCODED_VIDEOS_PROPERTY);
        assertTrue(transcodedVideos.isEmpty());

        String post = "/management/videos/recompute/";

        // generating new video renditions
        MultivaluedMap<String, String> formData = new MultivaluedMapImpl();

        if (query != null) {
            formData.add("query", query);
        }
        if (expectedRenditions != null) {
            for (String rendition : expectedRenditions) {
                formData.add("conversionName", rendition);
            }
        }

        String commandId;
        try (CloseableClientResponse response = httpClientRule.post(post, formData)) {
            if (impossibleConversionError) {
                assertEquals(SC_BAD_REQUEST, response.getStatus());
                return;
            }
            assertEquals(SC_OK, response.getStatus());
            JsonNode node = mapper.readTree(response.getEntityInputStream());
            assertBulkStatusScheduled(node);
            commandId = getBulkCommandId(node);
        }

        // waiting for the asynchronous video renditions recompute task
        txFeature.nextTransaction();

        try (CloseableClientResponse response = httpClientRule.get("/management/bulk/" + commandId)) {
            JsonNode node = mapper.readTree(response.getEntityInputStream());
            assertEquals(SC_OK, response.getStatus());
            assertBulkStatusCompleted(node);
            doc = session.getDocument(docRef);

            transcodedVideos = (List<Map<String, Serializable>>) doc.getPropertyValue(TRANSCODED_VIDEOS_PROPERTY);
            assertNotNull(transcodedVideos);
            if (success) {
                assertEquals(1, node.get(STATUS_PROCESSED).asInt());
                assertFalse(node.get(STATUS_HAS_ERROR).asBoolean());
                assertEquals(0, node.get(STATUS_ERROR_COUNT).asInt());
                assertEquals(1, node.get(STATUS_TOTAL).asInt());
                int nbExpectedRenditions;
                if (expectedRenditions == null) {
                    nbExpectedRenditions = 3;
                } else {
                    nbExpectedRenditions = expectedRenditions.size();
                }

                assertEquals(nbExpectedRenditions, transcodedVideos.size());
                switch (nbExpectedRenditions) {
                case 1:
                    assertEquals("WebM 480p", transcodedVideos.get(0).get("name"));

                    break;
                case 2:
                    assertEquals("WebM 480p", transcodedVideos.get(0).get("name"));
                    assertEquals("MP4 480p", transcodedVideos.get(1).get("name"));
                    break;

                default:
                    assertEquals("WebM 480p", transcodedVideos.get(0).get("name"));
                    assertEquals("MP4 480p", transcodedVideos.get(1).get("name"));
                    assertEquals("Ogg 480p", transcodedVideos.get(2).get("name"));
                    break;
                }
            } else {
                assertEquals(0, node.get(STATUS_PROCESSED).asInt());
                assertTrue(node.get(STATUS_HAS_ERROR).asBoolean());
                assertEquals(1, node.get(STATUS_ERROR_COUNT).asInt());
                assertEquals(0, node.get(STATUS_TOTAL).asInt());
                assertEquals("Invalid query", node.get(STATUS_ERROR_MESSAGE).asText());
                assertTrue(transcodedVideos.isEmpty());
            }
        }
    }

    @Test
    public void testRecomputeOneAfterRecomputeAll() throws IOException {
        DocumentModel doc = session.getDocument(docRef);
        String post = "/management/videos/recompute/";

        // generating all default video renditions
        MultivaluedMap<String, String> formData = new MultivaluedMapImpl();
        String commandId;
        try (CloseableClientResponse response = httpClientRule.post(post, formData)) {
            assertEquals(SC_OK, response.getStatus());
            JsonNode node = mapper.readTree(response.getEntityInputStream());
            assertBulkStatusScheduled(node);
            commandId = getBulkCommandId(node);
        }

        // waiting for the asynchronous video renditions recompute task
        txFeature.nextTransaction();

        try (CloseableClientResponse response = httpClientRule.get("/management/bulk/" + commandId)) {
            JsonNode node = mapper.readTree(response.getEntityInputStream());
            assertEquals(SC_OK, response.getStatus());
            assertBulkStatusCompleted(node);
            doc = session.getDocument(docRef);

            List<Map<String, Serializable>> transcodedVideos = (List<Map<String, Serializable>>) doc.getPropertyValue(
                    TRANSCODED_VIDEOS_PROPERTY);
            assertNotNull(transcodedVideos);
            System.out.println("Round 1->"+transcodedVideos);
            assertEquals(3, transcodedVideos.size());
        }

        // try recomputing only the MP4 conversion
        formData.add("conversionName", "MP4 480p");
        try (CloseableClientResponse response = httpClientRule.post(post, formData)) {
            assertEquals(SC_OK, response.getStatus());
            JsonNode node = mapper.readTree(response.getEntityInputStream());
            assertBulkStatusScheduled(node);
            commandId = getBulkCommandId(node);
        }

        // waiting for the asynchronous video renditions recompute task
        txFeature.nextTransaction();

        try (CloseableClientResponse response = httpClientRule.get("/management/bulk/" + commandId)) {
            JsonNode node = mapper.readTree(response.getEntityInputStream());
            assertEquals(SC_OK, response.getStatus());
            assertBulkStatusCompleted(node);
            doc = session.getDocument(docRef);

            List<Map<String, Serializable>> transcodedVideos = (List<Map<String, Serializable>>) doc.getPropertyValue(
                    TRANSCODED_VIDEOS_PROPERTY);
            assertNotNull(transcodedVideos);
            System.out.println("Round 1->"+transcodedVideos);
            assertEquals(3, transcodedVideos.size());
        }
    }
}
