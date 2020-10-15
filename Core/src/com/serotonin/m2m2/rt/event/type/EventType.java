/*
    Copyright (C) 2014 Infinite Automation Systems Inc. All rights reserved.
    @author Matthew Lohbihler
 */
package com.serotonin.m2m2.rt.event.type;

import java.io.IOException;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import com.infiniteautomation.mango.permission.MangoPermission;
import com.infiniteautomation.mango.spring.service.PermissionService;
import com.serotonin.json.JsonException;
import com.serotonin.json.JsonReader;
import com.serotonin.json.ObjectWriter;
import com.serotonin.json.spi.JsonEntity;
import com.serotonin.json.spi.JsonSerializable;
import com.serotonin.json.type.JsonObject;
import com.serotonin.m2m2.db.dao.DataPointDao;
import com.serotonin.m2m2.db.dao.DataSourceDao;
import com.serotonin.m2m2.db.dao.EventDetectorDao;
import com.serotonin.m2m2.db.dao.PublisherDao;
import com.serotonin.m2m2.i18n.TranslatableJsonException;
import com.serotonin.m2m2.module.EventTypeDefinition;
import com.serotonin.m2m2.module.ModuleRegistry;
import com.serotonin.m2m2.util.ExportCodes;
import com.serotonin.m2m2.util.ExportNames;
import com.serotonin.m2m2.vo.User;
import com.serotonin.m2m2.vo.dataSource.DataSourceVO;
import com.serotonin.m2m2.vo.permission.PermissionHolder;
import com.serotonin.m2m2.vo.publish.PublisherVO;

/**
 * An event class specifies the type of event that was raised.
 *
 * @author Matthew Lohbihler
 */
//Required to prevent properties from being written
@JsonEntity
abstract public class EventType implements JsonSerializable {
    public interface EventTypeNames {
        /**
         * Data points raise events with point event detectors. All point event detectors are stored in a single table,
         * so that the id of the detector is a unique identifier for the type. Thus, the detector's id can be (and is)
         * used as the event type id.
         */
        String DATA_POINT = "DATA_POINT";

        /**
         * Data sources raise events internally for their own reasons (for example no response from the external system)
         * or if a point locator failed. Data source error types are enumerated in the data sources themselves. So, the
         * unique identifier of a data source event type is the combination of the the data source id and the data
         * source error type.
         */
        String DATA_SOURCE = "DATA_SOURCE";

        /**
         * The system itself is also, of course, a producer of events (for example low disk space). The types of system
         * events are enumerated in the SystemEvents class. The system event type is the unique identifier for system
         * events.
         */
        String SYSTEM = "SYSTEM";

        /**
         * Publishers raise events internally for their own reasons, including general publishing failures or failures
         * in individual points. Error types are enumerated in the publishers themselves. So, the unique identifier of a
         * publisher event type is the combination of the publisher id and the publisher error type.
         */
        String PUBLISHER = "PUBLISHER";

        /**
         * Audit events are created when a user makes a change that needs to be acknowledged by other users. Such
         * changes include modifications to point event detectors, data sources, data points, and elements in modules
         * that define themselves.
         */
        String AUDIT = "AUDIT";

        /**
         * Compound detector event types have their unique identifiers generated by the database. These detectors listen
         * to point event detectors and scheduled events and raise events according to their configured logical
         * statement.
         */
        String COMPOUND = "COMPOUND";

        /**
         * Missing event types are a placeholder to load events created by modules that have been un-installed and left events remaining.
         */
        String MISSING = "MISSING";
    }

    public static final ExportNames SOURCE_NAMES = new ExportNames();

    public static void initialize() {
        SOURCE_NAMES.addElement(EventTypeNames.COMPOUND, "COMPOUND");
        SOURCE_NAMES.addElement(EventTypeNames.DATA_POINT);
        SOURCE_NAMES.addElement(EventTypeNames.DATA_SOURCE);
        SOURCE_NAMES.addElement(EventTypeNames.SYSTEM);
        SOURCE_NAMES.addElement(EventTypeNames.PUBLISHER);
        SOURCE_NAMES.addElement(EventTypeNames.AUDIT);
        SOURCE_NAMES.addElement(EventTypeNames.MISSING);

        for (EventTypeDefinition def : ModuleRegistry.getDefinitions(EventTypeDefinition.class))
            SOURCE_NAMES.addElement(def.getTypeName());
    }

    abstract public String getEventType();
    abstract public String getEventSubtype();

    /**
     * Convenience method that keeps us from having to cast.
     *
     * @return false here, but the system message implementation will return true.
     */
    public boolean isSystemMessage() {
        return false;
    }

    /**
     * Determines if the event type is subject to rate limiting.
     *
     * @return false here, but all event types to which this should apply should return true.
     */
    public boolean isRateLimited() {
        return false;
    }

    /**
     * Convenience method that keeps us from having to cast.
     *
     * @return -1 here, but the data source implementation will return the data source id.
     */
    public int getDataSourceId() {
        return -1;
    }

    /**
     * Convenience method that keeps us from having to cast.
     *
     * @return -1 here, but the data point implementation will return the data point id.
     */
    public int getDataPointId() {
        return -1;
    }

    /**
     * Convenience method that keeps us from having to cast.
     *
     * @return -1 here, but the publisher implementation will return the publisher id.
     */
    public int getPublisherId() {
        return -1;
    }

    /**
     * Determines whether an event type that, once raised, will always first be deactivated or whether overriding events
     * can be raised. Overrides can occur in data sources and point locators where a retry of a failed action causes the
     * same event type to be raised without the previous having returned to normal.
     *
     * @return whether this event type can be overridden with newer event instances.
     */
    abstract public DuplicateHandling getDuplicateHandling();

    abstract public int getReferenceId1();

    abstract public int getReferenceId2();

    /**
     * Check to see if a user has permission to this specific event type
     * @param user
     * @return
     */
    abstract public boolean hasPermission(PermissionHolder user, PermissionService service);

    /**
     * This is the permission that will be stored on the event in the database
     *  and used to determine access.  This is applied during raising the event
     *  and one can assume the context is filled
     *
     * @param context from raised event
     * @return
     */
    abstract public MangoPermission getEventPermission(Map<String, Object> context, PermissionService service);

    /**
     * Determines if the notification of this event to the given user should be suppressed. Useful if the action of the
     * user resulted in the event being raised.
     *
     * @return
     */
    public boolean excludeUser(User user) {
        return false;
    }

    //
    //
    // Serialization
    //
    @Override
    public void jsonRead(JsonReader reader, JsonObject jsonObject) throws JsonException {
        // no op. See the factory
    }

    @Override
    public void jsonWrite(ObjectWriter writer) throws IOException, JsonException {
        writer.writeEntry("sourceType", getEventType());
    }

    protected int getInt(JsonObject json, String name, ExportCodes codes) throws JsonException {
        String text = json.getString(name);
        if (text == null)
            throw new TranslatableJsonException("emport.error.eventType.missing", name, codes.getCodeList());

        int i = codes.getId(text);
        if (i == -1)
            throw new TranslatableJsonException("emport.error.eventType.invalid", name, text, codes.getCodeList());

        return i;
    }

    protected String getString(JsonObject json, String name, ExportNames codes) throws JsonException {
        String text = json.getString(name);
        if (text == null)
            throw new TranslatableJsonException("emport.error.eventType.missing", name, codes.getCodeList());

        if (!codes.hasCode(text))
            throw new TranslatableJsonException("emport.error.eventType.invalid", name, text, codes.getCodeList());

        return text;
    }

    protected int getDataPointId(JsonObject json, String name) throws JsonException {
        String xid = json.getString(name);
        if (xid == null)
            throw new TranslatableJsonException("emport.error.eventType.missing.reference", name);
        Integer dpid = DataPointDao.getInstance().getIdByXid(xid);
        if (dpid == null)
            throw new TranslatableJsonException("emport.error.eventType.invalid.reference", name, xid);
        return dpid;
    }

    protected int getPointEventDetectorId(JsonObject json, String dpName, String pedName) throws JsonException {
        return getPointEventDetectorId(json, getDataPointId(json, dpName), pedName);
    }

    protected int getPointEventDetectorId(JsonObject json, int dpId, String pedName) throws JsonException {
        String pedXid = json.getString(pedName);
        if (pedXid == null)
            throw new TranslatableJsonException("emport.error.eventType.missing.reference", pedName);
        int id = EventDetectorDao.getInstance().getId(pedXid, dpId);
        if (id == -1)
            throw new TranslatableJsonException("emport.error.eventType.invalid.reference", pedName, pedXid);

        return id;
    }

    protected DataSourceVO getDataSource(JsonObject json, String name) throws JsonException {
        String xid = json.getString(name);
        if (xid == null)
            throw new TranslatableJsonException("emport.error.eventType.missing.reference", name);
        DataSourceVO ds = DataSourceDao.getInstance().getByXid(xid);
        if (ds == null)
            throw new TranslatableJsonException("emport.error.eventType.invalid.reference", name, xid);
        return ds;
    }

    protected PublisherVO<?> getPublisher(JsonObject json, String name) throws JsonException {
        String xid = json.getString(name);
        if (xid == null)
            throw new TranslatableJsonException("emport.error.eventType.missing.reference", name);
        PublisherVO<?> pb = PublisherDao.getInstance().getByXid(xid);
        if (pb == null)
            throw new TranslatableJsonException("emport.error.eventType.invalid.reference", name, xid);
        return pb;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof EventType) {
            EventType other = (EventType)obj;
            if(     this.getReferenceId1() == other.getReferenceId1() &&
                    this.getReferenceId2() == other.getReferenceId2() &&
                    StringUtils.equals(this.getEventType(), other.getEventType()) &&
                    StringUtils.equals(this.getEventSubtype(), other.getEventSubtype()))
                return true;

        }
        return false;
    }

}
