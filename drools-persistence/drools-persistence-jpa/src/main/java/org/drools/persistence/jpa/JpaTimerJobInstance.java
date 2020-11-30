/*
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.drools.persistence.jpa;

import java.util.Date;

import javax.transaction.SystemException;

import org.kie.api.openicar.KnowledgeService;
import org.kie.api.openicar.KnowledgeServiceLocator;
import org.kie.api.runtime.ExecutableRunner;
import org.kie.api.time.SessionClock;
import org.drools.core.time.InternalSchedulerService;
import org.drools.core.time.Job;
import org.drools.core.time.JobContext;
import org.drools.core.time.JobHandle;
import org.drools.core.time.TimerService;
import org.drools.core.time.Trigger;
import org.drools.core.time.impl.CommandServiceTimerJobFactoryManager;
import org.drools.core.time.impl.DefaultTimerJobInstance;
import org.drools.core.time.impl.IntervalTrigger;
import org.drools.core.time.impl.PointInTimeTrigger;
import org.drools.core.time.impl.TimerJobInstance;
import org.jboss.seam.contexts.Contexts;
import org.jboss.seam.contexts.Lifecycle;
import org.jboss.seam.transaction.Transaction;
import org.jboss.seam.util.Work;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JpaTimerJobInstance extends DefaultTimerJobInstance {       

    private static Logger logger = LoggerFactory.getLogger( JpaTimerJobInstance.class );
    
    public JpaTimerJobInstance(Job job,
                               JobContext ctx,
                               Trigger trigger,
                               JobHandle handle,
                               InternalSchedulerService scheduler) {
        super( job,
               ctx,
               trigger,
               handle,
               scheduler );
    }

    public Void call() throws Exception {
        boolean appContextActive = Contexts.isApplicationContextActive();
        if (!appContextActive) Lifecycle.beginCall();
        rollbackIfNecessary();
        try {
            Work<Object> work = new Work<Object>() {
                @Override
                protected Object work() throws Exception {

            JDKCallableJobCommand command = new JDKCallableJobCommand( JpaTimerJobInstance.this );
            ExecutableRunner runner = ( (CommandServiceTimerJobFactoryManager) ( (TimerService) scheduler ).getTimerJobFactoryManager() ).getRunner();
            runner.execute( command );
            return null;
                }
            };
            work.workInTransaction();
            return null;
        } catch( Exception e ) { 
            logger.error("Unable to execute timer job!", e);
            throw e;
        }
    }

	protected void rollbackIfNecessary() {
		boolean needRollback = false;
		try {
			needRollback = Contexts.isApplicationContextActive() && !Transaction.instance().isNoTransaction();
		} catch (SystemException e1) {
			logger.error("cannot check tx!", e1);
		}
		if (needRollback) {
			logger.warn("force tx rollback!");
			try {
				Transaction.instance().rollback();
			} catch ( Exception e ) {
				logger.error("cannot rollback tx!", e);
			}
		}
	}

	Void internalCall() throws Exception {
        try {
        	if (!KnowledgeServiceLocator.getInstance().isStarted()) {
                getTrigger().nextFireTime(); // need to pop
        		if (getJobHandle().isCancel()) {
                    return null;
                }
        		rescheduleTimerCall();
        		return null;
        	}
			return super.call();
		} catch (Exception e) {
			try {
	            if (getJobHandle().isCancel()) {
	                return null;
	            }
				rescheduleTimerCall();
			} catch (Exception e2) {
				e2.printStackTrace();
			}
            try {
				Transaction.instance().setRollbackOnly();
			} catch (Exception e1) {
				e1.printStackTrace();
			}
            throw e;
		}
    }

	protected void rescheduleTimerCall() {
		//JobContext originJobContext = getJobContext();
		//if (originJobContext instanceof SelfRemovalJobContext) originJobContext = ((SelfRemovalJobContext) originJobContext).getJobContext();
		//ProcessJobContext pctx = (ProcessJobContext) (originJobContext instanceof ProcessJobContext ? originJobContext : null);
		Trigger trigger = getTrigger();
		Date date = trigger != null ? trigger.hasNextFireTime() : null;
		TimerJobInstance timerJobInstance = this;
		KnowledgeService knowledgeService = KnowledgeServiceLocator.getInstance();
		SessionClock sessionClock = //pctx.getKnowledgeRuntime().getSessionClock();
				knowledgeService.getStatefulKnowledgeSession().getSessionClock();
		long newNextFireTime = sessionClock.getCurrentTime() + knowledgeService.getTimerOverdueDelay();
		if (date != null && date.getTime() > newNextFireTime)
			newNextFireTime = date.getTime();
		if (trigger instanceof IntervalTrigger) {
			((IntervalTrigger) trigger).setNextFireTime(new Date(newNextFireTime));
		} else {
			trigger = PointInTimeTrigger.createPointInTimeTrigger(newNextFireTime, null);
			timerJobInstance = new JpaTimerJobInstance(getJob(), getJobContext(), trigger, getJobHandle(), scheduler);
		}
		// reschedule timer
		scheduler.internalSchedule(timerJobInstance);
	}
}
