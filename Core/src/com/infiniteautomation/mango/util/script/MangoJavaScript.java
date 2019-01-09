/**
 * Copyright (C) 2019  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.util.script;

import java.util.Map;

import com.serotonin.m2m2.rt.dataImage.IDataPointValueSource;
import com.serotonin.m2m2.rt.script.ScriptPermissions;

/**
 * Container for script execution/validation/testing
 * @author Terry Packer
 *
 */
public class MangoJavaScript {
    private boolean compile;  //Is this script to be compiled
    private String script;
    private Map<String, IDataPointValueSource> context;
    private String permissions;
    private ScriptLogLevels logLevel;
    //If non-null coerce the result into a PointValueTime with this data type
    private Integer resultDataTypeId; 
    
    /**
     * @return the compile
     */
    public boolean isCompile() {
        return compile;
    }
    /**
     * @param compile the compile to set
     */
    public void setCompile(boolean compile) {
        this.compile = compile;
    }
    /**
     * @return the script
     */
    public String getScript() {
        return script;
    }
    /**
     * @param script the script to set
     */
    public void setScript(String script) {
        this.script = script;
    }
    /**
     * @return the context
     */
    public Map<String, IDataPointValueSource> getContext() {
        return context;
    }
    /**
     * @param context the context to set
     */
    public void setContext(Map<String, IDataPointValueSource> context) {
        this.context = context;
    }
    /**
     * @return the permissions
     */
    public String getPermissions() {
        return permissions;
    }
    /**
     * @param permissions the permissions to set
     */
    public void setPermissions(String permissions) {
        this.permissions = permissions;
    }
    /**
     * @return the logLevel
     */
    public ScriptLogLevels getLogLevel() {
        return logLevel;
    }
    /**
     * @param logLevel the logLevel to set
     */
    public void setLogLevel(ScriptLogLevels logLevel) {
        this.logLevel = logLevel;
    }
    
    /**
     * @return the resultDataTypeId
     */
    public Integer getResultDataTypeId() {
        return resultDataTypeId;
    }
    
    /**
     * @param resultDataTypeId the resultDataTypeId to set
     */
    public void setResultDataTypeId(Integer resultDataTypeId) {
        this.resultDataTypeId = resultDataTypeId;
    }
    
    public ScriptPermissions createScriptPermissions() {
        //TODO Clean up 
        ScriptPermissions permissions = new ScriptPermissions();
        permissions.setDataPointReadPermissions(this.permissions);
        return permissions;
    }
}
