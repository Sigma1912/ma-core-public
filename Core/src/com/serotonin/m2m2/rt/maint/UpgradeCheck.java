/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.rt.maint;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.infiniteautomation.mango.monitor.IntegerMonitor;
import com.infiniteautomation.mango.monitor.ValueMonitorOwner;
import com.infiniteautomation.mango.spring.service.ModulesService;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.db.dao.SystemSettingsDao;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.module.ModuleRegistry;
import com.serotonin.m2m2.rt.event.ReturnCause;
import com.serotonin.m2m2.rt.event.type.DuplicateHandling;
import com.serotonin.m2m2.rt.event.type.SystemEventType;
import com.serotonin.timer.FixedRateTrigger;
import com.serotonin.timer.TimerTask;

/**
 * @author Matthew Lohbihler
 */
public class UpgradeCheck extends TimerTask implements ValueMonitorOwner{

    private static final Log LOG = LogFactory.getLog(UpgradeCheck.class);
    private static final long DELAY_TIMEOUT = 1000 * 10; // Run initially after 10 seconds
    private static final long PERIOD_TIMEOUT = 1000 * 60 * 60 * 24; // Run every 24 hours.

    public static final String UPGRADES_AVAILABLE_MONITOR_ID = "com.serotonin.m2m2.rt.maint.UpgradeCheck.COUNT";
    private IntegerMonitor availableUpgrades;

    /**
     * This method will set up the upgrade checking job. It assumes that the corresponding system setting for running
     * this job is true.
     */
    public static void start() {
        Common.backgroundProcessing.schedule(new UpgradeCheck());
    }

    private final SystemEventType et = new SystemEventType(SystemEventType.TYPE_UPGRADE_CHECK, 0,
            DuplicateHandling.IGNORE);

    public UpgradeCheck() {
        super(new FixedRateTrigger(DELAY_TIMEOUT, PERIOD_TIMEOUT), "Upgrade check task", "UpgradeCheck", 0);
    }

    @Override
    public void run(long fireTime) {
        Integer available = null;
        try {
            //If upgrade checks are not enabled we won't contact the store at all
            if(SystemSettingsDao.instance.getBooleanValue(SystemSettingsDao.UPGRADE_CHECKS_ENABLED)) {
                available = Common.getBean(ModulesService.class).upgradesAvailable();
            }
            
            if (available != null && available > 0) {

                TranslatableMessage m = new TranslatableMessage("modules.event.upgrades",
                        ModuleRegistry.getModule("mangoUI") != null ? "/ui/administration/modules" : "/modules.shtm");
                SystemEventType.raiseEvent(et, Common.timer.currentTimeMillis(), true, m);
            }
            else
                Common.eventManager.returnToNormal(et, Common.timer.currentTimeMillis(),
                        ReturnCause.RETURN_TO_NORMAL);
        }
        catch (Exception e) {
            LOG.error(e.getMessage(), e);
        } finally {
            //To ensure that the monitor is created, event if it is with a null value.
            if(this.availableUpgrades == null) {
                this.availableUpgrades = new IntegerMonitor(UPGRADES_AVAILABLE_MONITOR_ID, new TranslatableMessage("internal.monitor.AVAILABLE_UPGRADE_COUNT"), this, available);
                Common.MONITORED_VALUES.addIfMissingStatMonitor(this.availableUpgrades);
            } else
                this.availableUpgrades.setValue(available);
        }
    }

    @Override
    public void reset(String monitorId) {

    }
}
