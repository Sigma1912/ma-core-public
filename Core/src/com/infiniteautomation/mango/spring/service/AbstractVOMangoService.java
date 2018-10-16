/**
 * Copyright (C) 2018  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.service;

import org.apache.commons.lang3.StringUtils;

import com.infiniteautomation.mango.db.query.ConditionSortLimit;
import com.infiniteautomation.mango.util.exception.NotFoundException;
import com.infiniteautomation.mango.util.exception.ValidationException;
import com.serotonin.db.MappedRowCallback;
import com.serotonin.m2m2.db.dao.AbstractDao;
import com.serotonin.m2m2.i18n.ProcessResult;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.vo.AbstractVO;
import com.serotonin.m2m2.vo.User;
import com.serotonin.m2m2.vo.permission.PermissionException;
import com.serotonin.m2m2.vo.permission.PermissionHolder;
import com.serotonin.validation.StringValidation;

import net.jazdw.rql.parser.ASTNode;

/**
 * Base Service
 * 
 * @author Terry Packer
 *
 */
public abstract class AbstractVOMangoService<T extends AbstractVO<T>> {
    
    protected final AbstractDao<T> dao;
    
    public AbstractVOMangoService(AbstractDao<T> dao) {
        this.dao = dao;
    }
    
    /**
     * 
     * @param vo
     * @param user
     */
    public void ensureValid(T vo, PermissionHolder user) {
        ProcessResult result = new ProcessResult();
        if (StringUtils.isBlank(vo.getXid()))
            result.addContextualMessage("xid", "validate.required");
        else if (StringValidation.isLengthGreaterThan(vo.getXid(), 100))
            result.addMessage("xid", new TranslatableMessage("validate.notLongerThan", 100));
        else if (!dao.isXidUnique(vo.getXid(), vo.getId()))
            result.addContextualMessage("xid", "validate.xidUsed");

        if (StringUtils.isBlank(vo.getName()))
            result.addContextualMessage("name", "validate.required");
        else if (StringValidation.isLengthGreaterThan(vo.getName(), 255))
            result.addMessage("name", new TranslatableMessage("validate.notLongerThan", 255));
        
        ensureValidImpl(vo, user, result);
        if(!result.isValid())
            throw new ValidationException(result);
    }
    
    /**
     * 
     * @param xid
     * @param user
     * @return
     * @throws NotFoundException
     * @throws PermissionException
     * @throws ValidationException
     */
    public T get(String xid, PermissionHolder user) throws NotFoundException, PermissionException, ValidationException {
        return get(xid, user, false);
    }
    
    /**
     * Get relational data too
     * @param xid
     * @param user
     * @return
     * @throws NotFoundException
     * @throws PermissionException
     * @throws ValidationException
     */
    public T getFull(String xid, PermissionHolder user) throws NotFoundException, PermissionException, ValidationException {
        return get(xid, user, true);
    }
    
    /**
     * 
     * @param xid
     * @param user
     * @param full
     * @return
     */
    protected T get(String xid, PermissionHolder user, boolean full) {
        T vo;
        if(full)
            vo = dao.getFullByXid(xid);
        else
            vo = dao.getByXid(xid);
           
        if(vo == null)
            throw new NotFoundException();
        ensureReadPermission(user, vo);
        return vo;
    }
    
    /**
     * Insert a vo with its relational data
     * @param vo
     * @param user
     * @return
     * @throws PermissionException
     * @throws ValidationException
     */
    public T insertFull(T vo, PermissionHolder user) throws PermissionException, ValidationException {
        return insert(vo, user, true);
    }
    
    /**
     * Insert a vo without its relational data
     * @param vo
     * @param user
     * @return
     * @throws PermissionException
     * @throws ValidationException
     */
    public T insert(T vo, PermissionHolder user) throws PermissionException, ValidationException {
        return insert(vo, user, false);
    }

    
    
    /**
     * 
     * @param vo
     * @param user
     * @param full
     * @return
     * @throws PermissionException
     * @throws ValidationException
     */
    protected T insert(T vo, PermissionHolder user, boolean full) throws PermissionException, ValidationException {
        //Ensure they can create a list
        ensureCreatePermission(user);
        
        //Generate an Xid if necessary
        if(StringUtils.isEmpty(vo.getXid()))
            vo.setXid(dao.generateUniqueXid());
        
        ensureValid(vo, user);
        if(full)
            dao.saveFull(vo);
        else
            dao.save(vo);
        return vo;
    }

    /**
     * Update a vo without its relational data
     * @param existingXid
     * @param vo
     * @param user
     * @return
     * @throws PermissionException
     * @throws ValidationException
     */
    public T update(String existingXid, T vo, User user) throws PermissionException, ValidationException {
        return update(get(existingXid, user), vo, user);
    }


    /**
     * Update a vo without its relational data
     * @param existing
     * @param vo
     * @param user
     * @return
     * @throws PermissionException
     * @throws ValidationException
     */
    public T update(T existing, T vo, PermissionHolder user) throws PermissionException, ValidationException {
       return update(existing, vo, user, false);
    }
    
    /**
     * Update a vo and its relational data
     * @param existingXid
     * @param vo
     * @param user
     * @return
     * @throws PermissionException
     * @throws ValidationException
     */
    public T updateFull(String existingXid, T vo, User user) throws PermissionException, ValidationException {
        return updateFull(get(existingXid, user), vo, user);
    }


    /**
     * Update a vo and its relational data
     * @param existing
     * @param vo
     * @param user
     * @return
     * @throws PermissionException
     * @throws ValidationException
     */
    public T updateFull(T existing, T vo, PermissionHolder user) throws PermissionException, ValidationException {
        return update(existing, vo, user, true);
    }
    
    protected T update(T existing, T vo, PermissionHolder user, boolean full) throws PermissionException, ValidationException {
        ensureEditPermission(user, existing);
        vo.setId(existing.getId());
        ensureValid(vo, user);
        if(full)
            dao.saveFull(vo);
        else
            dao.save(vo);
        return vo;
    }

    
    /**
     * 
     * @param xid
     * @param user
     * @return
     * @throws PermissionException
     */
    public T delete(String xid, PermissionHolder user) throws PermissionException {
        T vo = get(xid, user);
        ensureEditPermission(user, vo);
        dao.delete(vo.getId());
        return vo;
    }
    
    /**
     * Query for VOs without returning the relational info
     * @param conditions
     * @param callback
     */
    public void customizedQuery(ConditionSortLimit conditions, MappedRowCallback<T> callback) {
        dao.customizedQuery(conditions, callback);
    }
    
    /**
     * Query for VOs and load the relational info
     * @param conditions
     * @param callback
     */
    public void customizedQuery(ASTNode conditions, MappedRowCallback<T> callback) {
        dao.customizedQuery(dao.rqlToCondition(conditions), callback);
    }
    
    /**
     * Query for VOs and load the relational info
     * @param conditions
     * @param callback
     */
    public void customizedQueryFull(ConditionSortLimit conditions, MappedRowCallback<T> callback) {
        dao.customizedQuery(conditions, (item, index) ->{
            dao.loadRelationalData(item);
            callback.row(item, index);
        });
    }
    
    /**
     * Query for VOs and collect the relational info
     * @param conditions
     * @param callback
     */
    public void customizedQueryFull(ASTNode conditions, MappedRowCallback<T> callback) {
        dao.customizedQuery(dao.rqlToCondition(conditions), (item, index) ->{
            dao.loadRelationalData(item);
            callback.row(item, index);
        });
    }
    
    /**
     * Count VOs
     * @param conditions
     * @return
     */
    public int customizedCount(ConditionSortLimit conditions) {
        return dao.customizedCount(conditions);
    }
    
    /**
     * Count VOs
     * @param conditions - RQL AST Node
     * @return
     */
    public int customizedCount(ASTNode conditions) {
        return dao.customizedCount(dao.rqlToCondition(conditions));
    }

    /**
     * Can this user create any VOs
     * 
     * @param user
     * @return
     */
    public abstract boolean hasCreatePermission(PermissionHolder user);
    
    /**
     * Can this user edit this VO
     * 
     * @param user
     * @param vo
     * @return
     */
    public abstract boolean hasEditPermission(PermissionHolder user, T vo);
    
    /**
     * Can this user read this VO
     * 
     * @param user
     * @param vo
     * @return
     */
    public abstract boolean hasReadPermission(PermissionHolder user, T vo);

    /**
     * Ensure this user can create a vo
     * 
     * @param user
     * @throws PermissionException
     */
    public abstract void ensureCreatePermission(PermissionHolder user) throws PermissionException;
    
    /**
     * Ensure this user can edit this vo
     * 
     * @param user
     * @param vo
     */
    public abstract void ensureEditPermission(PermissionHolder user, T vo) throws PermissionException;
    
    /**
     * Ensure this user can read this vo
     * 
     * @param user
     * @param vo
     * @throws PermissionException
     */
    public abstract void ensureReadPermission(PermissionHolder user, T vo) throws PermissionException;
    
    /**
     * Validate the VO, not necessary to throw exception as this will be done in ensureValid()
     * @param vo
     * @param user
     * @param result
     */
    protected abstract void ensureValidImpl(T vo, PermissionHolder user, ProcessResult result);
    

}
