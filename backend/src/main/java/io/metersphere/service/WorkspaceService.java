package io.metersphere.service;

import io.metersphere.base.domain.*;
import io.metersphere.base.mapper.UserMapper;
import io.metersphere.base.mapper.UserRoleMapper;
import io.metersphere.base.mapper.WorkspaceMapper;
import io.metersphere.base.mapper.ext.ExtOrganizationMapper;
import io.metersphere.base.mapper.ext.ExtUserRoleMapper;
import io.metersphere.base.mapper.ext.ExtWorkspaceMapper;
import io.metersphere.commons.constants.RoleConstants;
import io.metersphere.commons.exception.MSException;
import io.metersphere.controller.request.WorkspaceRequest;
import io.metersphere.dto.UserRoleHelpDTO;
import io.metersphere.dto.WorkspaceDTO;
import io.metersphere.dto.WorkspaceMemberDTO;
import io.metersphere.i18n.Translator;
import io.metersphere.user.SessionUser;
import io.metersphere.user.SessionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(rollbackFor = Exception.class)
public class WorkspaceService {
    @Resource
    private WorkspaceMapper workspaceMapper;
    @Resource
    private ExtWorkspaceMapper extWorkspaceMapper;
    @Resource
    private ExtUserRoleMapper extUserRoleMapper;
    @Resource
    private UserRoleMapper userRoleMapper;
    @Resource
    private UserMapper userMapper;
    @Resource
    private ExtOrganizationMapper extOrganizationMapper;

    public Workspace saveWorkspace(Workspace workspace) {
        if (StringUtils.isBlank(workspace.getName())) {
            MSException.throwException(Translator.get("workspace_name_is_null"));
        }
        // set organization id
        workspace.setOrganizationId(SessionUtils.getCurrentOrganizationId());

        long currentTime = System.currentTimeMillis();
        if (StringUtils.isBlank(workspace.getId())) {
            WorkspaceExample example = new WorkspaceExample();
            example.createCriteria()
                    .andOrganizationIdEqualTo(SessionUtils.getCurrentOrganizationId())
                    .andNameEqualTo(workspace.getName());
            if (workspaceMapper.countByExample(example) > 0) {
                MSException.throwException(Translator.get("workspace_name_already_exists"));
            }
            workspace.setId(UUID.randomUUID().toString()); // 设置ID
            workspace.setCreateTime(currentTime);
            workspace.setUpdateTime(currentTime); // 首次 update time
            workspaceMapper.insertSelective(workspace);
        } else {
            workspace.setUpdateTime(currentTime);
            workspaceMapper.updateByPrimaryKeySelective(workspace);
        }
        return workspace;
    }

    public List<Workspace> getWorkspaceList(WorkspaceRequest request) {
        WorkspaceExample example = new WorkspaceExample();
        WorkspaceExample.Criteria criteria = example.createCriteria();
        if (StringUtils.isNotBlank(request.getOrganizationId())) {
            criteria.andOrganizationIdEqualTo(request.getOrganizationId());
        }
        if (StringUtils.isNotBlank(request.getName())) {
            criteria.andNameLike(StringUtils.wrapIfMissing(request.getName(), "%"));
        }
        return workspaceMapper.selectByExample(example);
    }

    public List<WorkspaceDTO> getAllWorkspaceList(WorkspaceRequest request) {
        if (StringUtils.isNotBlank(request.getName())) {
            request.setName(StringUtils.wrapIfMissing(request.getName(), "%"));
        }
        return extWorkspaceMapper.getWorkspaceWithOrg(request);
    }

    public void deleteWorkspace(String workspaceId) {
        workspaceMapper.deleteByPrimaryKey(workspaceId);
    }

    /**
     * ORG_ADMIN需要检查是否有操作此工作空间的权限
     */
    public void checkWorkspaceOwnerByOrgAdmin(String workspaceId) {
        checkWorkspaceIsExist(workspaceId);
        WorkspaceExample example = new WorkspaceExample();
        SessionUser user = SessionUtils.getUser();
        List<String> orgIds = user.getUserRoles().stream()
                .filter(ur -> RoleConstants.ORG_ADMIN.equals(ur.getRoleId()))
                .map(UserRole::getSourceId)
                .collect(Collectors.toList());
        example.createCriteria()
                .andOrganizationIdIn(orgIds)
                .andIdEqualTo(workspaceId);
        if (workspaceMapper.countByExample(example) == 0) {
            MSException.throwException(Translator.get("workspace_does_not_belong_to_user"));
        }
    }

    public void checkWorkspaceOwnerByTestManager(String workspaceId) {
        checkWorkspaceIsExist(workspaceId);
        SessionUser user = SessionUtils.getUser();
        List<String> wsIds = user.getUserRoles().stream()
                .filter(ur -> RoleConstants.TEST_MANAGER.equals(ur.getRoleId()))
                .map(UserRole::getSourceId)
                .collect(Collectors.toList());
        boolean contains = wsIds.contains(workspaceId);
        if (!contains) {
            MSException.throwException(Translator.get("workspace_does_not_belong_to_user"));
        }
    }

    public void checkWorkspaceOwner(String workspaceId) {
        checkWorkspaceIsExist(workspaceId);
        WorkspaceExample example = new WorkspaceExample();
        SessionUser user = SessionUtils.getUser();
        List<String> orgIds = user.getUserRoles().stream()
                .filter(ur -> RoleConstants.ORG_ADMIN.equals(ur.getRoleId()))
                .map(UserRole::getSourceId)
                .collect(Collectors.toList());
        example.createCriteria()
                .andOrganizationIdIn(orgIds)
                .andIdEqualTo(workspaceId);
        List<String> wsIds = user.getUserRoles().stream()
                .filter(ur -> RoleConstants.TEST_MANAGER.equals(ur.getRoleId()))
                .map(UserRole::getSourceId)
                .collect(Collectors.toList());
        boolean contains = wsIds.contains(workspaceId);
        if (workspaceMapper.countByExample(example) == 0 && !contains) {
            MSException.throwException(Translator.get("workspace_does_not_belong_to_user"));
        }
    }

    public void checkWorkspaceIsExist(String workspaceId) {
        WorkspaceExample example = new WorkspaceExample();
        example.createCriteria().andIdEqualTo(workspaceId);
        if (workspaceMapper.countByExample(example) == 0) {
            MSException.throwException("workspace_not_exist");
        }
    }

    public List<Workspace> getWorkspaceListByUserId(String userId) {
        List<UserRoleHelpDTO> userRoleHelpList = extUserRoleMapper.getUserRoleHelpList(userId);
        List<String> workspaceIds = new ArrayList<>();
        userRoleHelpList.forEach(r -> {
            if (!StringUtils.isEmpty(r.getParentId())) {
                workspaceIds.add(r.getSourceId());
            }
        });
        WorkspaceExample workspaceExample = new WorkspaceExample();
        workspaceExample.createCriteria().andIdIn(workspaceIds);
        return workspaceMapper.selectByExample(workspaceExample);
    }

    public List<Workspace> getWorkspaceListByOrgIdAndUserId(String orgId) {
        String useId = SessionUtils.getUser().getId();
        WorkspaceExample workspaceExample = new WorkspaceExample();
        workspaceExample.createCriteria().andOrganizationIdEqualTo(orgId);
        List<Workspace> workspaces = workspaceMapper.selectByExample(workspaceExample);
        UserRoleExample userRoleExample = new UserRoleExample();
        userRoleExample.createCriteria().andUserIdEqualTo(useId);
        List<UserRole> userRoles = userRoleMapper.selectByExample(userRoleExample);
        List<Workspace> resultWorkspaceList = new ArrayList<>();
        userRoles.forEach(userRole -> {
            workspaces.forEach(workspace -> {
                if (StringUtils.equals(userRole.getSourceId(), workspace.getId())) {
                    if (!resultWorkspaceList.contains(workspace)) {
                        resultWorkspaceList.add(workspace);
                    }
                }
            });
        });
        return resultWorkspaceList;
    }

    public void updateWorkspaceMember(WorkspaceMemberDTO memberDTO) {
        User user = new User();
        BeanUtils.copyProperties(memberDTO, user);
        userMapper.updateByPrimaryKeySelective(user);
        //
        String workspaceId = memberDTO.getWorkspaceId();
        String userId = user.getId();
        // 已有角色
        List<Role> memberRoles = extUserRoleMapper.getWorkspaceMemberRoles(workspaceId, userId);
        // 修改后的角色
        List<String> roles = memberDTO.getRoleIds();
        List<String> allRoleIds = memberRoles.stream().map(Role::getId).collect(Collectors.toList());
        // 更新用户时添加了角色
        for (int i = 0; i < roles.size(); i++) {
            if (checkSourceRole(workspaceId, userId, roles.get(i)) == 0) {
                UserRole userRole = new UserRole();
                userRole.setId(UUID.randomUUID().toString());
                userRole.setUserId(userId);
                userRole.setRoleId(roles.get(i));
                userRole.setSourceId(workspaceId);
                userRole.setCreateTime(System.currentTimeMillis());
                userRole.setUpdateTime(System.currentTimeMillis());
                userRoleMapper.insertSelective(userRole);
            }
        }
        allRoleIds.removeAll(roles);
        if (allRoleIds.size() > 0) {
            UserRoleExample userRoleExample = new UserRoleExample();
            userRoleExample.createCriteria().andUserIdEqualTo(userId)
                    .andSourceIdEqualTo(workspaceId)
                    .andRoleIdIn(allRoleIds);
            userRoleMapper.deleteByExample(userRoleExample);
        }
    }

    public Integer checkSourceRole(String orgId, String userId, String roleId) {
        return extOrganizationMapper.checkSourceRole(orgId, userId, roleId);
    }

    public void updateWorkspacebyAdmin(Workspace workspace) {
        workspace.setCreateTime(null);
        workspace.setUpdateTime(System.currentTimeMillis());
        workspaceMapper.updateByPrimaryKeySelective(workspace);
    }

    public void addWorkspaceByAdmin(Workspace workspace) {
        if (StringUtils.isBlank(workspace.getName())) {
            MSException.throwException(Translator.get("workspace_name_is_null"));
        }
        if (StringUtils.isBlank(workspace.getOrganizationId())) {
            MSException.throwException(Translator.get("organization_id_is_null"));
        }
        WorkspaceExample example = new WorkspaceExample();
        example.createCriteria()
                .andOrganizationIdEqualTo(workspace.getOrganizationId())
                .andNameEqualTo(workspace.getName());
        if (workspaceMapper.countByExample(example) > 0) {
            MSException.throwException(Translator.get("workspace_name_already_exists"));
        }
        workspace.setId(UUID.randomUUID().toString());
        workspace.setCreateTime(System.currentTimeMillis());
        workspace.setUpdateTime(System.currentTimeMillis());
        workspaceMapper.insertSelective(workspace);
    }
}
