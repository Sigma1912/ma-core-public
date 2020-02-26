/**
 * Copyright (C) 2019  Infinite Automation Software. All rights reserved.
 */
package com.infiniteautomation.mango.spring.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.infiniteautomation.mango.spring.db.RoleTableDefinition;
import com.infiniteautomation.mango.util.Functions;
import com.infiniteautomation.mango.util.exception.NotFoundException;
import com.infiniteautomation.mango.util.exception.ValidationException;
import com.serotonin.m2m2.Common;
import com.serotonin.m2m2.db.dao.RoleDao;
import com.serotonin.m2m2.i18n.ProcessResult;
import com.serotonin.m2m2.i18n.TranslatableMessage;
import com.serotonin.m2m2.module.ModuleRegistry;
import com.serotonin.m2m2.module.PermissionDefinition;
import com.serotonin.m2m2.vo.AbstractVO;
import com.serotonin.m2m2.vo.permission.PermissionException;
import com.serotonin.m2m2.vo.permission.PermissionHolder;
import com.serotonin.m2m2.vo.role.Role;
import com.serotonin.m2m2.vo.role.RoleVO;

/**
 * @author Terry Packer
 *
 */
@Service
public class RoleService extends AbstractVOService<RoleVO, RoleTableDefinition, RoleDao> {

    @Autowired
    public RoleService(RoleDao dao, PermissionService permissionService) {
        super(dao, permissionService);
    }

    @Override
    public boolean hasEditPermission(PermissionHolder user, RoleVO vo) {
        return permissionService.hasAdminRole(user);
    }

    @Override
    public boolean hasReadPermission(PermissionHolder user, RoleVO vo) {
        return permissionService.isValidPermissionHolder(user);
    }

    @Override
    public RoleVO delete(RoleVO vo)
            throws PermissionException, NotFoundException {
        //Cannot delete the 'user' or 'superadmin' roles
        if(StringUtils.equalsIgnoreCase(vo.getXid(), getSuperadminRole().getXid())) {
            PermissionHolder user = Common.getUser();
            throw new PermissionException(new TranslatableMessage("roles.cannotAlterSuperadminRole"), user);
        }else if(StringUtils.equalsIgnoreCase(vo.getXid(), getUserRole().getXid())) {
            PermissionHolder user = Common.getUser();
            throw new PermissionException(new TranslatableMessage("roles.cannotAlterUserRole"), user);
        }
        return super.delete(vo);
    }

    @Override
    public ProcessResult validate(RoleVO vo, PermissionHolder user) {
        ProcessResult result = super.validate(vo, user);

        //Don't allow the use of role 'user' or 'superadmin'
        if(StringUtils.equalsIgnoreCase(vo.getXid(), getSuperadminRole().getXid())) {
            result.addContextualMessage("xid", "roles.cannotAlterSuperadminRole");
        }
        if(StringUtils.equalsIgnoreCase(vo.getXid(), getUserRole().getXid())) {
            result.addContextualMessage("xid", "roles.cannotAlterUserRole");
        }

        //Don't allow spaces in the XID
        Matcher matcher = Functions.WHITESPACE_PATTERN.matcher(vo.getXid());
        if(matcher.find()) {
            result.addContextualMessage("xid", "validate.role.noSpaceAllowed");
        }
        return result;
    }

    @Override
    public ProcessResult validate(RoleVO existing, RoleVO vo, PermissionHolder user) {
        ProcessResult result = this.validate(vo, user);
        if(!StringUtils.equals(existing.getXid(), vo.getXid())) {
            result.addContextualMessage("xid", "validate.role.cannotChangeXid");
        }
        return result;
    }

    /**
     * Get the roles for a permission
     * @param permissionType
     * @return
     */
    public Set<Role> getRoles(String permissionType) {
        PermissionHolder user = Common.getUser();
        Objects.requireNonNull(user, "Permission holder must be set in security context");

        permissionService.ensureAdminRole(user);
        return this.dao.getRoles(permissionType);
    }

    /**
     * Remove a role from a permission type
     * @param role
     * @param permissionType
     * @param user
     */
    public void removeRoleFromPermission(Role role, String permissionType) throws PermissionException, NotFoundException {
        PermissionHolder user = Common.getUser();
        Objects.requireNonNull(user, "Permission holder must be set in security context");

        permissionService.ensureAdminRole(user);
        Set<Role> permissionRoles = this.dao.getRoles(permissionType);
        if(!permissionRoles.contains(role)) {
            throw new NotFoundException();
        }

        dao.removeRoleFromPermission(role, permissionType);
    }

    /**
     * Add a role to a permission type
     * @param role
     * @param permissionType
     */
    public void addRoleToPermission(Role role, String permissionType) throws PermissionException, ValidationException {
        PermissionHolder user = Common.getUser();
        Objects.requireNonNull(user, "Permission holder must be set in security context");

        permissionService.ensureAdminRole(user);
        Set<Role> permissionRoles = this.dao.getRoles(permissionType);
        if(permissionRoles.contains(role)) {
            ProcessResult result = new ProcessResult();
            result.addGenericMessage("roleAlreadyAssignedToPermission", role.getXid(), permissionType);
            throw new ValidationException(result);
        }

        dao.addRoleToPermission(role, permissionType);
    }

    /**
     *
     * @param roleXids
     * @param permissionType
     */
    public void replaceAllRolesOnPermission(Set<String> roleXids, String permissionType) {
        PermissionHolder user = Common.getUser();
        Objects.requireNonNull(user, "Permission holder must be set in security context");

        permissionService.ensureAdminRole(user);

        PermissionDefinition def = ModuleRegistry.getPermissionDefinition(permissionType);
        if(def == null) {
            throw new NotFoundException();
        }

        ProcessResult validation = new ProcessResult();
        Set<Role> roles = new HashSet<>();
        if(roleXids != null) {
            for(String xid : roleXids) {
                try {
                    RoleVO roleVO = get(xid);
                    roles.add(roleVO.getRole());
                }catch(NotFoundException e) {
                    validation.addGenericMessage("validate.role.notFound", xid);
                }
            }
        }

        if(validation.getHasMessages()) {
            throw new ValidationException(validation);
        }

        dao.replaceRolesOnPermission(roles, def.getPermissionTypeName());
    }

    /**
     * Replace all roles to a permission type
     * @param role
     * @param permissionType
     */
    public void replaceAllRolesOnPermission(Set<Role> roles, PermissionDefinition def) throws PermissionException, ValidationException {
        PermissionHolder user = Common.getUser();
        Objects.requireNonNull(user, "Permission holder must be set in security context");
        permissionService.ensureAdminRole(user);
        dao.replaceRolesOnPermission(roles, def.getPermissionTypeName());
    }

    /**
     * Add a role to a permission
     * @param voId
     * @param role
     * @param permissionType
     */
    public void addRoleToVoPermission(Role role, AbstractVO vo, String permissionType) throws ValidationException {
        PermissionHolder user = Common.getUser();
        Objects.requireNonNull(user, "Permission holder must be set in security context");

        permissionService.ensureAdminRole(user);
        //TODO Mango 4.0 PermissionHolder check?
        // Superadmin ok
        // holder must contain the role already?
        //Cannot add an existing mapping
        Set<Role> roles = this.dao.getRoles(vo.getId(), vo.getClass().getSimpleName(), permissionType);
        if(roles.contains(role)) {
            ProcessResult result = new ProcessResult();
            result.addGenericMessage("role.alreadyAssignedToPermission", role.getXid(), permissionType,  vo.getClass().getSimpleName());
            throw new ValidationException(result);
        }
        this.dao.addRoleToVoPermission(role, vo, permissionType);
    }

    /**
     * Get the superadmin role
     * @return
     */
    public Role getSuperadminRole() {
        return PermissionHolder.SUPERADMIN_ROLE;
    }

    /**
     * Get the default user role
     * @return
     */
    public Role getUserRole() {
        return PermissionHolder.USER_ROLE;
    }
}
