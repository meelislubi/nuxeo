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
 *     Anahide Tchertchian
 */
package org.nuxeo.ecm.platform.web.common.idempotency;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.DeferredFileOutputStream;

/**
 * Response wrapper that can capture response result, using a {@link DeferredFileOutputStream}.
 *
 * @since 11.5
 */
public class CopyingResponseWrapper extends HttpServletResponseWrapper {

    private final DeferredFileOutputStream capture;

    private ServletOutputStream output;

    private PrintWriter writer;

    public CopyingResponseWrapper(int threshold, HttpServletResponse response) {
        super(response);
        capture = new DeferredFileOutputStream(threshold, response.getBufferSize(), "nxidem", null, null);
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called on this response.");
        }

        if (output == null) {
            output = new CopyingServletOutputStream(getResponse().getOutputStream(), capture);
        }

        return output;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (output != null) {
            throw new IllegalStateException("getOutputStream() has already been called on this response.");
        }

        if (writer == null) {
            writer = new PrintWriter(new OutputStreamWriter(
                    new CopyingServletOutputStream(getResponse().getOutputStream(), capture), getCharacterEncoding()));
        }

        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        super.flushBuffer();

        if (writer != null) {
            writer.flush();
        } else if (output != null) {
            output.flush();
        }
    }

    public byte[] getCaptureAsBytes() throws IOException {
        if (writer != null) {
            writer.close();
        } else if (output != null) {
            output.close();
        }

        if (capture.isInMemory()) {
            return capture.getData();
        } else {
            return FileUtils.readFileToByteArray(capture.getFile());
        }
    }

}
