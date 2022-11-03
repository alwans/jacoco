/*******************************************************************************
 * Copyright (c) 2009, 2021 Mountainminds GmbH & Co. KG and Contributors
 * This program and the accompanying materials are made available under
 * the terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *    Marc R. Hoffmann - initial API and implementation
 *
 *******************************************************************************/
package org.jacoco.core.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mario.common.repo.dto.ClassInfo;
import com.mario.common.repo.dto.MethodInfo;
import com.mario.common.repo.enums.ChangeTypeEnum;
import org.apache.commons.lang3.StringUtils;
import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.MethodProbesInfo;
import org.jacoco.core.internal.analysis.ClassCoverageImpl;
import org.jacoco.core.utils.HttpUtils;
import org.jacoco.core.utils.JacksonUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

/**
 * @author wl
 * @date 2022/10/3
 * @Description TODO
 **/
public class ExecMergeHandler {

	private static final int HTTP_CODE = 200;

	/**
	 * 获取code diff 接口
	 */
	private static final String DIFF_API_URL = "http://127.0.0.1:%s/api/code/diff/list";
	/**
	 * 查找指定class文件路径 接口
	 */
	private static final String CLASSFILE_API_URL = "http://127.0.0.1:%s/api/source/classfile";

	/**
	 * 项目id
	 */
	private int projectId;

	/**
	 * coverage 服务端口
	 */
	private int coveragePort;

	/**
	 * 日志输出
	 */
	private PrintWriter out;

	public ExecMergeHandler(int projectId, int coveragePort) {
		this.projectId = projectId;
		this.coveragePort = coveragePort;
		this.out = new PrintWriter(System.out);
	}

	public ExecFileLoader mergeExecHandle(List<ExecFileLoader> loaders)
			throws IOException {
		ExecFileLoader execFileLoader = null;
		for (ExecFileLoader exec : loaders) {
			if (execFileLoader == null) {
				execFileLoader = exec;
				continue;
			}
			mergeExec(execFileLoader, exec);
		}
		return execFileLoader;
	}

	private void mergeExec(ExecFileLoader newExec, ExecFileLoader oldExec)
			throws IOException {
		// 不同分支, 暂时不处理这种情况
		if (!newExec.getBranchName().equals(oldExec.getBranchName())) {
			throw new RuntimeException("不同分支数据，不支持数据合并！！！");
		}
		// 合并sessionInfo信息
		newExec.getSessionInfoStore()
				.mergeOldInfos(oldExec.getSessionInfoStore().getInfos());

		// 同分支,同commit id : 走jacoco自己的合并逻辑
		if (newExec.getCommitId().equals(oldExec.getCommitId())) {
			// 合并ExecutionData数据
			for (ExecutionData data : oldExec.getExecutionDataStore()
					.getContents()) {
				newExec.getExecutionDataStore().put(data);
			}
			return;
		}
		// 同分支,不同commit id
		if (!newExec.getCommitId().equals(oldExec.getCommitId())) {
			// 先获取code diff
			List<ClassInfo> classInfoList = getCodeDiff(projectId,
					newExec.getBranchName(), oldExec.getCommitId(),
					newExec.getCommitId());
			/**
			 * { className: test, diffType: modify | add | delete
			 * List<MethodInfo> { methodName: A, diffType: modify | add | delete
			 *
			 * methodName: B, diffType: modify | add | delete } }
			 */
			mergeDiffVersionExec(classInfoList, newExec, oldExec);
		}
	}

	private void mergeDiffVersionExec(List<ClassInfo> diffClasses,
			ExecFileLoader newExec, ExecFileLoader oldExec) throws IOException {
		List<MethodInfo> allModifyMethod = new ArrayList<MethodInfo>();
		// absolute path of the modified file
		List<File> newModifyClassFiles = new ArrayList<File>();
		// absolute path of the file before modification
		List<File> oldModifyClassFiles = new ArrayList<File>();
		// absolute path of deleted file
		List<File> deleteClassFiles = new ArrayList<File>();
		// modify diff class asm name
		List<String> diffModifyClassNames = new ArrayList<String>();
		// delete diff class asm name
		List<String> diffDelClassNames = new ArrayList<String>();
		// all diff class asm name
		List<String> diffClassNames = new ArrayList<String>();
		for (ClassInfo classInfo : diffClasses) {
			diffClassNames.add(classInfo.getClassName());
			if (classInfo.getType() == ChangeTypeEnum.MODIFY) {
				diffModifyClassNames.add(classInfo.getClassName());
				newModifyClassFiles.add(new File(
						getNewClassFilePath(projectId, newExec, classInfo)));
				oldModifyClassFiles.add(new File(
						getOldClassFilePath(projectId, oldExec, classInfo)));
			}
			if (classInfo.getType() == ChangeTypeEnum.DELETE) {
				diffDelClassNames.add(classInfo.getClassName());
				deleteClassFiles.add(new File(
						getOldClassFilePath(projectId, oldExec, classInfo)));
			}
		}
		Map<String, IClassCoverage> newCoverageMap = classAnalysis(
				newExec.getExecutionDataStore(), newModifyClassFiles);
		Map<String, IClassCoverage> oldCoverageMap = classAnalysis(
				oldExec.getExecutionDataStore(), oldModifyClassFiles);
		Map<String, IClassCoverage> delCoverageMap = classAnalysis(
				oldExec.getExecutionDataStore(), deleteClassFiles);
		// 找到被删除方法的uri
		for (String className : diffDelClassNames) {
			ClassCoverageImpl delCoverage = (ClassCoverageImpl) delCoverageMap
					.get(className);
			List<MethodInfo> deleteMethodInfos = getDiffMethods(diffClasses,
					className);
			if (delCoverage == null) {
				out.println(className + ": 未找到匹配的探针数据");
				continue;
			}
			for (MethodProbesInfo oldInfo : delCoverage
					.getMethodProbesInfos()) {
				// 这里什么都没做，不懂是想干嘛
				// isContainsMethod(className, deleteMethodInfos,
				// oldInfo.getMethodUri());
			}
			allModifyMethod.addAll(deleteMethodInfos);
		}

		for (String className : diffModifyClassNames) {
			ClassCoverageImpl newCoverage = (ClassCoverageImpl) newCoverageMap
					.get(className);
			ClassCoverageImpl oldCoverage = (ClassCoverageImpl) oldCoverageMap
					.get(className);
			List<MethodInfo> diffMethodInfos = getDiffMethods(diffClasses,
					className);
			if (newCoverage == null || oldCoverage == null) {
				out.println(className + ": 未找到匹配的探针数据!");
				continue;
			}
			// 开始遍历老版本中修改类的探针数据，对未修改方法的探针数据进行合并
			for (MethodProbesInfo oldInfo : oldCoverage
					.getMethodProbesInfos()) {
				// 修改类中，如果方法未修改或者未删除，就合并探针数据
				if (!isContainsMethod(diffMethodInfos, oldInfo.getMethodName(),
						oldInfo.getDesc())) {
					MethodProbesInfo newInfo = getNewMPI(
							newCoverage.getMethodProbesInfos(),
							oldInfo.getMethodName());
					int length = newInfo.getEndIndex() - newInfo.getStartIndex()
							+ 1;
					int newStartIndex = newInfo.getStartIndex();
					int oldStartIndex = oldInfo.getStartIndex();
					boolean[] newProbes = getTargetDataProbes(
							newExec.getExecutionDataStore(), className);
					boolean[] oldProbes = getTargetDataProbes(
							oldExec.getExecutionDataStore(), className);
					if (Objects.isNull(oldProbes)) {
						// ignore
						continue;
					} else if (Objects.isNull(newProbes)
							&& !Objects.isNull(oldProbes)) {
						// new exec modify class not init
						// get probes size
						int newProbesSize = 0;
						for (MethodProbesInfo info : newCoverage
								.getMethodProbesInfos()) {
							int len = info.getEndIndex() - info.getStartIndex()
									+ 1;
							newProbesSize += len;
						}
						newProbes = new boolean[newProbesSize];
						ExecutionData data = new ExecutionData(
								newCoverage.getId(), className, newProbes);
						newExec.getExecutionDataStore().put(data);
					}
					// 这里应该还要加上set数据的合并
					while (length-- > 0) {
						newProbes[newStartIndex] = newProbes[newStartIndex]
								| oldProbes[oldStartIndex];
						newStartIndex++;
						oldStartIndex++;
					}
				}
			}
			allModifyMethod.addAll(diffMethodInfos);
		}
		// 合并ExecutionData数据;排序修改类以及删除类
		for (ExecutionData data : oldExec.getExecutionDataStore()
				.getContents()) {
			if (!diffClassNames.contains(data.getName())) {
				newExec.getExecutionDataStore().put(data);
			}
		}
	}

	private boolean[] getTargetDataProbes(ExecutionDataStore executionDataStore,
			String className) {
		Iterator<ExecutionData> iterator = executionDataStore.getContents()
				.iterator();
		while (iterator.hasNext()) {
			ExecutionData data = iterator.next();
			if (className.equals(data.getName())) {
				return data.getProbes();
			}
		}
		return null;
	}

	private MethodProbesInfo getNewMPI(List<MethodProbesInfo> infos,
			String methodName) {
		for (MethodProbesInfo info : infos) {
			if (methodName.equals(info.getMethodName())) {
				return info;
			}
		}
		return null;
	}

	/**
	 * 匹配方法是否存在列表中
	 *
	 * @param methodInfos
	 *            方法列表
	 * @param methodName
	 *            方法名
	 * @param desc
	 *            asm方法参数信息
	 * @return
	 */
	private boolean isContainsMethod(List<MethodInfo> methodInfos,
			String methodName, String desc) {
		for (MethodInfo methodInfo : methodInfos) {
			if (StringUtils.isNotBlank(methodInfo.getMethodName())
					&& methodInfo.getMethodName().equals(methodName)
					&& MethodUriAdapter
							.checkParamsIn(methodInfo.getParameters(), desc)) {
				return true;

			}
		}
		return false;
	}

	private List<MethodInfo> getDiffMethods(List<ClassInfo> classInfos,
			String classFullName) {
		for (ClassInfo classInfo : classInfos) {
			if (classFullName.equals(classInfo.getClassName())) {
				return classInfo.getMethodInfos();
			}
		}
		return new ArrayList<MethodInfo>();
	}

	/**
	 * 分析类文件，找到每个方法的始末探针num
	 *
	 * @param data
	 *            探针数据
	 * @param classFiles
	 *            需要解析的class文件列表：class文件的绝对路径
	 * @return
	 * @throws IOException
	 */
	private Map<String, IClassCoverage> classAnalysis(ExecutionDataStore data,
			List<File> classFiles) throws IOException {

		final CoverageBuilder builder = new CoverageBuilder();
		final Analyzer analyzer = new Analyzer(data, builder);
		for (final File f : classFiles) {
			analyzer.analyzeAll(f);
		}
		return builder.getClassesMap();
	}

	/**
	 * 获取代码差异
	 *
	 * @param projectId
	 * @param branchName
	 * @param baseCommitId
	 * @param nowCommitId
	 * @return
	 */
	public List<ClassInfo> getCodeDiff(int projectId, String branchName,
			String baseCommitId, String nowCommitId) throws IOException {

		String url = String.format(DIFF_API_URL, coveragePort);

		Map<String, String> data = new HashMap<String, String>(6);
		data.put("id", String.valueOf(projectId));
		data.put("baseVersion", branchName);
		data.put("nowVersion", branchName);
		// diff
		data.put("baseCommitId", baseCommitId);
		data.put("nowCommitId", nowCommitId);
		// 暂时先写死，因为不支持不同分支合并，所以这里只会是commit
		data.put("diffType", "1");
		String jsonData = new GsonBuilder().create().toJson(data);
		HttpUtils.Resp resp = HttpUtils.doPost(url, jsonData);

		if (!resp.isSuccess()) {
			throw new RuntimeException("接口" + url + ": 请求失败！");
		}
		HttpUtils.Result result = resp.getResult();
		if (HTTP_CODE == result.getCode()) {
			return JacksonUtils.deserializeArray(result.getData().toString(),
					ClassInfo.class);
		} else {
			throw new RuntimeException(result.getMsg());
		}
	}

	private String getNewClassFilePath(int projectId, ExecFileLoader exec,
			ClassInfo classInfo) {
		if (StringUtils.isNotEmpty(classInfo.getNewClassFile())) {
			return classInfo.getNewClassFile();
		}
		return getClassFilePath(projectId, exec.getBranchName(),
				exec.getCommitId(), classInfo.getClassName());
	}

	private String getOldClassFilePath(int projectId, ExecFileLoader exec,
			ClassInfo classInfo) {
		if (StringUtils.isNotEmpty(classInfo.getOldClassFile())) {
			return classInfo.getOldClassFile();
		}
		return getClassFilePath(projectId, exec.getBranchName(),
				exec.getCommitId(), classInfo.getClassName());
	}

	/**
	 * 获取对应class件的路径
	 *
	 * @param projectId
	 * @param branchName
	 * @param commitId
	 * @param className
	 * @return
	 */
	public String getClassFilePath(int projectId, String branchName,
			String commitId, String className) {
		String url = String.format(CLASSFILE_API_URL, coveragePort);
		Map<String, String> data = new HashMap<>();
		data.put("id", String.valueOf(projectId));
		data.put("branch", branchName);
		data.put("className", className);
		data.put("commitId", commitId);
		HttpUtils.Resp resp = HttpUtils.doGet(url, data);
		if (!resp.isSuccess()) {
			throw new RuntimeException("接口" + url + ": 请求失败！");
		}
		HttpUtils.Result result = resp.getResult();
		if (HTTP_CODE == result.getCode()) {
			return result.getData().toString();
		}
		throw new RuntimeException(
				"获取" + className + ", class文件路径失败, 原因： " + result.getMsg());
	}
}
