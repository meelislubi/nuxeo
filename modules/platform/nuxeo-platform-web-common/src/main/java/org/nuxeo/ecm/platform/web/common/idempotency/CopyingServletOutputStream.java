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

import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;

import org.apache.commons.io.output.DeferredFileOutputStream;

/**
 * Captures content written to the target stream.
 *
 * @since 11.5
 */
public class CopyingServletOutputStream extends ServletOutputStream {

    protected final ServletOutputStream output;

    protected final DeferredFileOutputStream capture;

    public CopyingServletOutputStream(ServletOutputStream output, DeferredFileOutputStream capture) {
        this.output = output;
        this.capture = capture;
    }

    @Override
    public void write(int b) throws IOException {
        output.write(b);
        capture.write(b);
    }

    @Override
    public void flush() throws IOException {
        output.flush();
        capture.flush();
    }

    @Override
    public void close() throws IOException {
        output.close();
        capture.close();
    }

    @Override
    public boolean isReady() {
        return output.isReady();
    }

    @Override
    public void setWriteListener(WriteListener writeListener) {
        output.setWriteListener(writeListener);
    }

}
