package org.nuxeo.runtime.registry;

import org.nuxeo.common.xmap.annotation.XNode;
import org.nuxeo.common.xmap.annotation.XObject;
import org.nuxeo.common.xmap.registry.XRegistry;
import org.nuxeo.common.xmap.registry.XRegistryId;

@XObject("descriptor")
@XRegistry(compatWarnOnMerge = true)
public class SampleWarnOnMergeDescriptor {

    @XRegistryId
    @XNode("@name")
    public String name;

    @XNode("value")
    public String value;

    @XNode(value = "bool")
    public Boolean bool;

}
