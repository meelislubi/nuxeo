/*
 * (C) Copyright 2006-2009 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Thierry Delprat
 */

package org.nuxeo.ecm.platform.wss.backend;

import org.nuxeo.wss.servlet.WSSRequest;
import org.nuxeo.wss.spi.WSSBackend;

public class SearchBasedVirtualHostedBackendfactory extends
        AbstractWSSBackendFactory {

    @Override
    protected WSSBackend createBackend(WSSRequest request) {
        String virtualRoot = computeVirtualRoot(request);
        NuxeoWSSBackend realBackend = new SearchBasedVirtualRootBackend(virtualRoot, null);
        return new VirtualRootedBackend(virtualRoot,realBackend);
    }

}