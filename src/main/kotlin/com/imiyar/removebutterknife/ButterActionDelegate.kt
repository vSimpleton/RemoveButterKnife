package com.imiyar.removebutterknife

import com.google.gson.JsonParser
import com.imiyar.removebutterknife.utils.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.*

class ButterActionDelegate(private val project: Project, private val vFile: VirtualFile, private val psiJavaFile: PsiJavaFile, private val psiClass: PsiClass) {

    private val elementFactory = JavaPsiFacade.getInstance(project).elementFactory
    private var mPsiClass = psiClass

    /**
     * 外部类使用，存储所有使用@BindView注解的变量名称，示例Pair<xml属性名称，class变量名称>
     */
    private val bindViewFieldsLists = mutableListOf<Pair<String, String>>()

    /**
     * 内部类使用，存储所有使用@BindView注解的变量名称，示例Pair<xml属性名称，class变量名称>
     */
    private val innerBindViewFieldsLists = mutableListOf<Pair<String, String>>()

    /**
     * 存储所有使用@OnClick注解的方法名称，示例Pair<xml属性名称，方法名称(参数)>
     */
    private val onClickMethodsLists = mutableListOf<Pair<String, String>>()
    private val onLongClickMethodsLists = mutableListOf<Pair<String, String>>()

    fun parse(): Boolean {
        if (!checkIsNeedModify()) {
            return false
        }

        // 新增Binding类实例并写进class的全局变量里
        // 并把setContentView()修改为使用setContentView(mBinding.root)，注意：区分Activity跟Fragment
        addViewBindingStatement()

        // 遍历保存所有使用@BindView注解的变量名称
        findAllBindViewAnnotation()

        // 遍历保存所有使用@OnClick注解的属性id以及对应的click方法
        findAllOnClickMethodAnnotation()

        // 删除ButterKnife的import语句、绑定语句、解绑语句
        deleteButterKnifeBindStatement()

        // 内部类
        handleInnerClass(mPsiClass.innerClasses)

        return true
    }

    /**
     * 添加ViewBinding的实例
     * 区分Activity、Fragment、自定义View、Dialog、Adapter
     */
    private fun addViewBindingStatement() {
        var methods = mPsiClass.findMethodsByName("onCreateView", false)
        if (methods.isEmpty()) {
            methods = mPsiClass.findMethodsByName("onCreate", false)
        }
        if (methods.isEmpty()) {
            methods = mPsiClass.constructors
        }

        if (methods.isNotEmpty()) {
            if (methods[0].isConstructor) {
                changeCustomViewBindingStatement(methods)
            } else {
                changeUIBindingStatement(methods)
            }
        }
    }

    /**
     * 修改Activity与Fragment的mBinding绑定语句
     */
    private fun changeSetContentViewStatement(bindingName: String, methods: Array<PsiMethod>) {
        run jump@{
            mPsiClass.methods.forEach {
                if (it.name == "onCreate") {
                    it.body?.statements?.forEach { statement ->
                        if (statement.firstChild.text.trim().contains("setContentView(")) {
                            val bindingStatement = elementFactory.createStatementFromText("setContentView(mBinding.getRoot());", it)
                            writeAction {
                                it.addBefore(bindingStatement, statement)
                                statement.delete()
                            }
                            return@jump
                        }
                    }
                } else if (it.name == "onCreateView") {
                    // 需要删除的语句锚点有：inflater.inflate( 、 return
                    it.body?.statements?.forEach { statement ->
                        if (statement.firstChild.text.trim().contains("inflater.inflate(")) {
                            // TODO 需要先判断方法是否有参数
                            val bindingStatement = elementFactory.createStatementFromText("mBinding = $bindingName.inflate(${it.parameters[0].name}, ${it.parameters[1].name}, false);", it)
                            writeAction {
                                it.addBefore(bindingStatement, statement)
                                statement.delete()
                            }
                        } else if (statement.firstChild.text.trim().contains("return")) {
                            val returnStatement = elementFactory.createStatementFromText("return mBinding.getRoot();", it)
                            writeAction {
                                it.addBefore(returnStatement, statement)
                                statement.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Activity与Fragment添加ViewBinding相关代码
     */
    private fun changeUIBindingStatement(methods: Array<PsiMethod>) {
        run jump@{
            methods[0].body?.statements?.forEach { statement ->
                // 拿到布局名称
                if (statement.firstChild.text.trim().contains("R.layout.")) {
                    val layoutRes = statement.firstChild.text.trim().getLayoutRes()
                    // 把布局名称转换成Binding实例名称。如activity_record_detail -> ActivityRecordDetailBinding
                    val bindingName = layoutRes.underLineToHump().withViewBinding()
                    // 把转换后的Binding实例名称以及实例化语句添加进class类里
                    var psiField: PsiField? = null
                    if (methods[0].name == "onCreate") {
                        psiField = elementFactory.createFieldFromText("private $bindingName mBinding = $bindingName.inflate(getLayoutInflater());\n", mPsiClass)
                    } else if (methods[0].name == "onCreateView" || methods[0].isConstructor) {
                        psiField = elementFactory.createFieldFromText("private $bindingName mBinding;\n", psiClass)
                    }

                    val importList = psiJavaFile.importList
                    val importStatement = elementFactory.createImportStatementOnDemand(getBindingJsonFile(layoutRes))

                    writeAction {
                        psiField?.let {
                            mPsiClass.addAfter(it, mPsiClass.allFields.last())
                            importList?.add(importStatement)
                        }
                    }

                    if (bindingName.isNotEmpty()) {
                        changeSetContentViewStatement(bindingName, methods)
                    }

                    return@jump
                }
            }
        }

    }

    /**
     * 自定义View添加ViewBinding相关代码
     */
    private fun changeCustomViewBindingStatement(methods: Array<PsiMethod>) {
        mPsiClass.methods.forEach { method ->
            method.body?.statements?.forEach { statement ->
                // 拿到布局名称
                if (statement.firstChild.text.trim().contains("R.layout.")) {
                    val layoutRes = statement.firstChild.text.trim().getLayoutRes()
                    // 把布局名称转换成Binding实例名称。如activity_record_detail -> ActivityRecordDetailBinding
                    val bindingName = layoutRes.underLineToHump().withViewBinding()
                    val psiField = elementFactory.createFieldFromText("private $bindingName mBinding;\n", mPsiClass)

                    val importList = psiJavaFile.importList
                    val importStatement = elementFactory.createImportStatementOnDemand(getBindingJsonFile(layoutRes))

                    val replaceStatement = if (!statement.text.contains("=")) {
                        elementFactory.createStatementFromText("View view = ${statement.text}", method)
                    } else {
                        elementFactory.createStatementFromText("View view = ${statement.text.trim().split("=")[1]}", method)
                    }
                    val bindingStatement = elementFactory.createStatementFromText("mBinding = $bindingName.bind(view);", method)

                    writeAction {
                        mPsiClass.addAfter(psiField, mPsiClass.allFields.last())
                        importList?.add(importStatement)
                        method.addBefore(replaceStatement, statement)
                        method.addAfter(bindingStatement, statement)
                        statement.delete()
                    }
                }
            }
        }
    }

    /**
     * 获取生成的Binding映射文件，并拿到该Binding类的包路径
     */
    private fun getBindingJsonFile(layoutRes: String): String {
        val listFilesName = mutableListOf<String>()
        var fileName = vFile.parent
        while (!fileName.toString().endsWith("src")) {
            fileName = fileName.parent
        }

        var bindImportClass = ""
        var moduleFile = File("${fileName.parent.path}/build/intermediates/data_binding_base_class_log_artifact/debug/out/")
        if (moduleFile.isDirectory) {
            if (moduleFile.listFiles() != null && moduleFile.listFiles().isNotEmpty()) {
                moduleFile = moduleFile.listFiles()[0]
            }
        }
        if (moduleFile.isFile) {
            val jsonObject = JsonParser.parseString(readJsonFile(moduleFile)).asJsonObject
            if (jsonObject.get("mappings").asJsonObject.get(layoutRes) != null) {
                bindImportClass = jsonObject.get("mappings").asJsonObject.get(layoutRes).asJsonObject.get("module_package").asString
            }
        }

        // 优先判断该文件所属的module下的json映射文件，若找不到需要的Binding类，再遍历所有module寻找json文件
        if (bindImportClass.isNotEmpty()) {
            return bindImportClass
        } else {
            val dataBindingDir = "${fileName.parent.parent.path}${File.separator}"
            val file = File(dataBindingDir)
            if (file.isDirectory) {
                file.listFiles()?.forEach {
                    if (it.isDirectory && !it.isHidden) {
                        listFilesName.add(it.absolutePath)
                    }
                }
            }

            listFilesName.forEach {
                val fileStr = "$it/build/intermediates/data_binding_base_class_log_artifact/debug/out/"
                var jsonFile = File(fileStr)
                if (jsonFile.isDirectory) {
                    if (jsonFile.listFiles() != null && jsonFile.listFiles().isNotEmpty()) {
                        jsonFile = jsonFile.listFiles()[0]
                    }
                }
                // 拿到json文件并拿到我们需要的Binding类包名，用于添加import
                if (jsonFile.isFile) {
                    val jsonObject = JsonParser.parseString(readJsonFile(jsonFile)).asJsonObject
                    if (jsonObject.get("mappings").asJsonObject.get(layoutRes) != null) {
                        bindImportClass = jsonObject.get("mappings").asJsonObject.get(layoutRes).asJsonObject.get("module_package").asString
                    }
                }
                if (bindImportClass.isNotEmpty()) {
                    return@forEach
                }
            }
        }

        return bindImportClass
    }

    /**
     * 把viewBinding生成的映射文件解析成json字符串
     */
    private fun readJsonFile(jsonFile: File): String {
        val sb = StringBuffer()
         BufferedReader(InputStreamReader(FileInputStream(jsonFile), "UTF-16BE")).useLines { lines ->
             lines.forEach {
                 sb.append(it)
             }
        }
        return sb.toString()
    }

    /**
     * 遍历保存所有使用@BindView注解的变量名称
     */
    private fun findAllBindViewAnnotation() {
        mPsiClass.fields.forEach {
            it.annotations.forEach { psiAnnotation ->
                if (psiAnnotation.qualifiedName?.contains("BindView") == true) {
                    val first = psiAnnotation.findAttributeValue("value")?.lastChild?.text.toString()
                    val second = it.name
                    bindViewFieldsLists.add(Pair(first, second))

                    writeAction {
                        // 删除@BindView注解相关的字段
                        it.delete()
                    }
                }
            }
        }

        // 把原本使用@BindView的变量xxx，改成mBinding.xxx(驼峰式命名)
        changeAllBindViewFields()
    }

    /**
     * 把原本使用@BindView的变量xxx，改成mBinding.xxx(驼峰式命名)
     */
    private fun changeAllBindViewFields() {
        mPsiClass.methods.forEach {
            it.body?.statements?.forEach { statement ->
                var replaceText = statement.text.trim()
                bindViewFieldsLists.forEachIndexed { index, pair ->
                    if (replaceText.isOnlyContainsTarget(pair.second) && !replaceText.isOnlyContainsTarget("R.id.${pair.second}")) {
                        replaceText = replaceText.replace("\\b${pair.second}\\b".toRegex(), "mBinding.${pair.first.underLineToHump()}")
                    }
                    if (index == bindViewFieldsLists.size - 1) {
                        if (replaceText != statement.text.trim()) {
                            val replaceStatement = elementFactory.createStatementFromText(replaceText, it)
                            writeAction {
                                it.addBefore(replaceStatement, statement)
                                statement.delete()
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 遍历保存所有使用@OnClick注解的属性id以及对应的click方法
     */
    private fun findAllOnClickMethodAnnotation() {
        mPsiClass.methods.forEach {
            it.annotations.forEach { psiAnnotation ->
                if (psiAnnotation.qualifiedName?.contains("OnClick") == true) {
                    psiAnnotation.findAttributeValue("value")?.text?.getAnnotationIds()?.forEach { id ->
                        var second = "${it.name}()"
                        if (it.parameters.isNotEmpty()) {
                            second = "${it.name}(view)"
                        }
                        onClickMethodsLists.add(Pair(id, second))
                    }

                    writeAction {
                        // 删除@OnClick注解
                        psiAnnotation.delete()
                    }
                } else if (psiAnnotation.qualifiedName?.contains("OnLongClick") == true) {
                    psiAnnotation.findAttributeValue("value")?.text?.getAnnotationIds()?.forEach { id ->
                        var second = "${it.name}()"
                        if (it.parameters.isNotEmpty()) {
                            second = "${it.name}(view)"
                        }
                        onLongClickMethodsLists.add(Pair(id, second))
                    }
                    writeAction {
                        // 删除@OnLongClick注解
                        psiAnnotation.delete()
                    }
                }
            }
        }

        // 把原本使用@OnClick注解的方法改为常规的ClickListener
        if (onClickMethodsLists.isNotEmpty() || onLongClickMethodsLists.isNotEmpty()) {
            addAllOnClickMethods()
        }
    }

    /**
     * 把原本使用@OnClick注解的方法改为常规的ClickListener
     */
    private fun addAllOnClickMethods() {
        val psiListenerMethod = mPsiClass.findMethodsByName("initListener", false)
        if (!psiListenerMethod.isNullOrEmpty()) {
            writeAction {
                onClickMethodsLists.forEach {
                    val statementStr = "mBinding.${it.first.underLineToHump()}.setOnClickListener(view -> ${it.second});\n"
                    val statement = elementFactory.createStatementFromText(statementStr, psiClass)
                    psiListenerMethod[0].lastChild.add(statement)
                }
                onLongClickMethodsLists.forEach {
                    val statementStr = "mBinding.${it.first.underLineToHump()}.setOnLongClickListener(view -> {\n" +
                            "${it.second};\n" +
                            "return false;\n" +
                            "});\n"
                    val statement = elementFactory.createStatementFromText(statementStr, psiClass)
                    psiListenerMethod[0].lastChild.add(statement)
                }
            }
        } else {
            var psiMethods = mPsiClass.findMethodsByName("onViewCreated", false)
            if (psiMethods.isEmpty()) {
                psiMethods = mPsiClass.findMethodsByName("onCreate", false)
            }
            if (psiMethods.isEmpty()) {
                psiMethods = mPsiClass.constructors
            }
            if (psiMethods.isNotEmpty()) {
                var methodIndex = 0
                psiMethods.forEachIndexed { index, method ->
                    if (method.parameters.size > psiMethods[methodIndex].parameters.size) {
                        methodIndex = index
                    }
                }

                val finalMethod = if (psiMethods[0].isConstructor) psiMethods[methodIndex] else psiMethods[0]
                val psiStatement = elementFactory.createStatementFromText("initListener();", finalMethod)
                val createMethod = elementFactory.createMethodFromText("private void initListener() {}\n", mPsiClass)

                writeAction {
                    onClickMethodsLists.forEach {
                        val statementStr = "mBinding.${it.first.underLineToHump()}.setOnClickListener(view -> ${it.second});\n"
                        val statement = elementFactory.createStatementFromText(statementStr, mPsiClass)
                        createMethod.lastChild.add(statement)
                    }

                    onLongClickMethodsLists.forEach {
                        val statementStr = "mBinding.${it.first.underLineToHump()}.setOnLongClickListener(view -> {\n" +
                                "${it.second};\n" +
                                "return false;\n" +
                                "});\n"
                        val statement = elementFactory.createStatementFromText(statementStr, mPsiClass)
                        createMethod.lastChild.add(statement)
                    }

                    finalMethod.addAfter(psiStatement, finalMethod.body?.statements?.last())
                    psiClass.addAfter(createMethod, finalMethod)
                }
            }
        }

    }

    /**
     * 删除ButterKnife的import语句、绑定语句、解绑语句
     */
    private fun deleteButterKnifeBindStatement() {
        writeAction {
            psiJavaFile.importList?.importStatements?.forEach {
                if (it.qualifiedName?.lowercase()?.contains("butterknife") == true) {
                    it.delete()
                }
            }

            mPsiClass.methods.forEach {
                it.body?.statements?.forEach { statement ->
                    if (statement.firstChild.text.trim().contains("ButterKnife.bind(")) {
                        statement.delete()
                    }
                }
            }

            val unBinderField = psiClass.fields.find {
                it.type.canonicalText.contains("Unbinder")
            }
            if (unBinderField != null) {
                mPsiClass.methods.forEach {
                    it.body?.statements?.forEach { statement ->
                        if (statement.firstChild.text.trim().contains(unBinderField.name)) {
                            statement.delete()
                        }
                    }
                }
                unBinderField.delete()
            }
        }
    }

    /**
     * 检查是否有import butterknife相关，若没有引入butterknife，则不需要操作
     */
    private fun checkIsNeedModify(): Boolean {
        val importStatement = psiJavaFile.importList?.importStatements?.find {
            it.qualifiedName?.lowercase(Locale.getDefault())?.contains("butterknife") == true
        }
        return importStatement != null
    }

    private fun handleInnerClass(innerClasses: Array<PsiClass>?) {
        innerClasses?.forEach {
            innerBindViewFieldsLists.clear()
            onClickMethodsLists.clear()
            onLongClickMethodsLists.clear()
            mPsiClass = it
            findInnerClassAllBindViewAnnotation()
            deleteButterKnifeBindStatement()
            handleInnerClass(it.innerClasses)
        }
    }

    private fun findInnerClassAllBindViewAnnotation() {
        mPsiClass.fields.forEach {
            it.annotations.forEach { psiAnnotation ->
                if (psiAnnotation.qualifiedName?.contains("BindView") == true) {
                    val first = psiAnnotation.findAttributeValue("value")?.lastChild?.text.toString()
                    val second = it.name
                    innerBindViewFieldsLists.add(Pair(first, second))

                    writeAction {
                        // 删除@BindView注解相关的字段
                        psiAnnotation.delete()
                    }
                }
            }
        }

        // 把原本使用@BindView的变量xxx，改成mBinding.xxx(驼峰式命名)
        // 注：这是因为内部类有可能引用外部类的xml属性，所以需要多一次判断
        changeAllBindViewFields()

        // 内部类的@BindView统一改成fvb形式
        changeInnerAllBindViewFields()
    }

    /**
     * 内部类的@BindView统一改成findViewById形式
     */
    private fun changeInnerAllBindViewFields() {
        mPsiClass.methods.forEach jump@{ method ->
            method.body?.statements?.forEach { statement ->
                if (statement.firstChild.text.trim().contains("ButterKnife.bind(")) {
                    innerBindViewFieldsLists.forEach { pair ->
                        val psiStatement = if (mPsiClass.name?.endsWith("Holder") == true || mPsiClass.name?.endsWith("VH") == true) {
                            elementFactory.createStatementFromText("${pair.second} = itemView.findViewById(R.id.${pair.first});", method)
                        } else {
                            elementFactory.createStatementFromText("${pair.second} = findViewById(R.id.${pair.first});", method)
                        }
                        writeAction {
                            method.addAfter(psiStatement, statement)
                        }
                    }
                    return@jump
                }
            }
        }
    }

    private fun writeAction(commandName: String = "RemoveButterKnifeWriteAction", runnable: Runnable) {
        WriteCommandAction.runWriteCommandAction(project, commandName, "RemoveButterKnifeGroupID", runnable, psiJavaFile)
    }

}