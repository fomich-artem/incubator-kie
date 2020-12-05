package org.drools.core.process.instance.impl;

import org.drools.core.process.instance.WorkItem;

/**
 * Hook for {@link WorkItem#setId()}
 * 
 * @author <a href="mailto:a.fomichev@comsoft-corp.ru">Fomichev Artem</a> <br>
 *
 */
public interface WorkItemSetIdHandler {

    void handleWorkItemSetId(long id);

}
