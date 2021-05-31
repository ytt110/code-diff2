package com.dr.code.diff.vercontrol.svn;

import com.dr.code.diff.config.CustomizeConfig;
import com.dr.code.diff.enums.CodeManageTypeEnum;
import com.dr.code.diff.util.PathUtils;
import com.dr.code.diff.util.SvnRepoUtil;
import com.dr.code.diff.vercontrol.AbstractVersionControl;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.SVNURL;
import org.tmatesoft.svn.core.wc.SVNDiffClient;
import org.tmatesoft.svn.core.wc.SVNRevision;

/**
 * @ProjectName: code-diff-parent
 * @Package: com.dr.code.diff.vercontrol
 * @Description: svn差异代码获取
 * @Author: duanrui
 * @CreateDate: 2021/4/5 9:56
 * @Version: 1.0
 * <p>
 * Copyright: Copyright (c) 2021
 */
@Component
public class SvnAbstractVersionControl extends AbstractVersionControl {

    @Autowired
    private CustomizeConfig customizeConfig;


    /**
     * 获取操作类型
     */
    @Override
    public CodeManageTypeEnum getType() {
        return CodeManageTypeEnum.SVN;
    }

    @Override
    public void getDiffCodeClasses() {
        try {
            MySVNDiffStatusHandler.list.clear();
            String nowSvnUrl = super.versionControlDto.getRepoUrl();
            if(StringUtils.isNotBlank(super.versionControlDto.getSvnRepoUrl())){
                nowSvnUrl = super.versionControlDto.getSvnRepoUrl();
            }
            SVNRevision oldVersion = null;
            SVNRevision newVersion = null;
            //不同reversion的比较和最新reversion的比较
            if (StringUtils.isNotBlank(super.versionControlDto.getNowVersion()) && StringUtils.isNotBlank(super.versionControlDto.getBaseVersion())) {
                oldVersion = SVNRevision.create(Long.parseLong(super.versionControlDto.getBaseVersion()));
                newVersion = SVNRevision.create(Long.parseLong(super.versionControlDto.getNowVersion()));
            } else {
                oldVersion = SVNRevision.HEAD;
                newVersion = SVNRevision.HEAD;
            }
            String localBaseRepoDir = SvnRepoUtil.getSvnLocalDir(super.versionControlDto.getRepoUrl(), customizeConfig.getSvnLocalBaseRepoDir(), super.versionControlDto.getBaseVersion());
            String localNowRepoDir = SvnRepoUtil.getSvnLocalDir(nowSvnUrl, customizeConfig.getSvnLocalBaseRepoDir(), super.versionControlDto.getNowVersion());
            SvnRepoUtil.cloneRepository(super.versionControlDto.getRepoUrl(), localBaseRepoDir, oldVersion, customizeConfig.getSvnUserName(), customizeConfig.getSvnPassWord());
            SvnRepoUtil.cloneRepository(nowSvnUrl, localNowRepoDir, newVersion, customizeConfig.getSvnUserName(), customizeConfig.getSvnPassWord());
            SVNDiffClient svnDiffClient = SvnRepoUtil.getSVNDiffClient(customizeConfig.getSvnUserName(), customizeConfig.getSvnPassWord());
            svnDiffClient.doDiffStatus(SVNURL.parseURIEncoded(super.versionControlDto.getRepoUrl()), oldVersion, SVNURL.parseURIEncoded(nowSvnUrl), newVersion, SVNDepth.INFINITY, true, new MySVNDiffStatusHandler());
            //将差异代码设置进集合
            super.versionControlDto.setDiffClasses(MySVNDiffStatusHandler.list);
        } catch (SVNException e) {
            e.printStackTrace();
        }
    }


    /**
     * @param filePackage
     * @date:2021/4/24
     * @className:VersionControl
     * @author:Administrator
     * @description: 获取旧版本文件本地路径
     */
    @Override
    public String getLocalNewPath(String filePackage) {
        String localDir = SvnRepoUtil.getSvnLocalDir(super.versionControlDto.getSvnRepoUrl(), customizeConfig.getSvnLocalBaseRepoDir(), "");
        return PathUtils.getClassFilePath(localDir, versionControlDto.getBaseVersion(), filePackage);
    }

    /**
     * @param filePackage
     * @date:2021/4/24
     * @className:VersionControl
     * @author:Administrator
     * @description: 获取新版本文件本地路径
     */
    @Override
    public String getLocalOldPath(String filePackage) {
        String localDir = SvnRepoUtil.getSvnLocalDir(super.versionControlDto.getRepoUrl(), customizeConfig.getSvnLocalBaseRepoDir(), "");
        return PathUtils.getClassFilePath(localDir, versionControlDto.getBaseVersion(), filePackage);
    }

}
