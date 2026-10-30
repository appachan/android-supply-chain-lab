// version catalog の宣言と解決結果を突き合わせる。理由は docs/adr/0003-version-resolution.md を参照

abstract class CheckResolvedVersions : DefaultTask() {
    // モジュール（group:name）から宣言した版への対応
    @get:Input abstract val declared: MapProperty<String, String>

    // 構成名から解決結果への対応。成果物はダウンロードしない
    @get:Input abstract val graphs: MapProperty<String, ResolvedComponentResult>

    @TaskAction
    fun check() {
        val drifts = graphs.get().flatMap { (name, root) ->
            driftsIn(root).map { drift -> drift to name }
        }.groupBy({ it.first }, { it.second })

        if (drifts.isNotEmpty()) {
            val lines = drifts.toSortedMap().map { (drift, names) -> "$drift in ${names.sorted()}" }
            throw GradleException(
                "Resolved versions differ from the declared versions:\n" +
                    lines.joinToString("\n") { "  $it" } +
                    "\nUpdate gradle/libs.versions.toml, or find the cause with dependencyInsight or resolvedPluginInsight."
            )
        }
    }

    private fun driftsIn(root: ResolvedComponentResult): Set<String> {
        val drifts = sortedSetOf<String>()
        val visited = mutableSetOf(root)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val from = queue.removeFirst()
            // platform の制約と plugin marker の実装は catalog に載らないので、宣言元の要求と比べる
            val comparesRequests = from.moduleName() in declared.get() && (from.isPlatform() || from.isPluginMarker())
            from.dependencies.filterIsInstance<UnresolvedDependencyResult>().forEach { edge ->
                drifts += "${edge.requested.displayName}: not resolved, ${edge.failure.message}"
            }
            from.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { edge ->
                val selected = edge.selected
                val module = selected.moduleName()
                val version = selected.moduleVersion?.version
                // 推移的にだけ見える catalog の項目は、その module が使っていないので比べない
                if (from.id is ProjectComponentIdentifier) {
                    declared.get()[module]?.takeIf { it != version }?.let { drifts += "$module: declared $it, resolved $version" }
                }

                val requested = (edge.requested as? ModuleComponentSelector)?.version
                if (comparesRequests && !requested.isNullOrEmpty() && requested != version) {
                    drifts += "$module: requested $requested by ${from.moduleName()}, resolved $version"
                }

                if (visited.add(selected)) queue.addLast(selected)
            }
        }
        return drifts
    }

    private fun ResolvedComponentResult.moduleName() = moduleVersion?.let { "${it.group}:${it.name}" }

    private fun ResolvedComponentResult.isPluginMarker() = moduleVersion?.name.orEmpty().endsWith(".gradle.plugin")

    private fun ResolvedComponentResult.isPlatform() = variants.any {
        it.attributes.getAttribute(Attribute.of("org.gradle.category", String::class.java)) in setOf("platform", "enforced-platform")
    }
}

val isRoot = project == rootProject

val checkResolvedVersions = tasks.register<CheckResolvedVersions>("checkResolvedVersions") {
    val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
    if (isRoot) {
        // plugin の classpath は root の buildscript に集まる。ライブラリの宣言とは別のグラフなので marker だけを比べる
        declared.putAll(
            libs.pluginAliases.map { libs.findPlugin(it).get().get() }
                .associate { "${it.pluginId}:${it.pluginId}.gradle.plugin" to it.version.requiredVersion }
        )
        graphs.put("classpath", project.buildscript.configurations.getByName("classpath").incoming.resolutionResult.rootComponent)
    } else {
        declared.putAll(
            libs.libraryAliases.map { libs.findLibrary(it).get().get() }
                .filter { it.versionConstraint.requiredVersion.isNotEmpty() }
                .associate { "${it.module}" to it.versionConstraint.requiredVersion }
        )
        configurations
            .matching { c -> c.isCanBeResolved && listOf("compileClasspath", "runtimeClasspath").any { c.name.endsWith(it, ignoreCase = true) } }
            .forEach { graphs.put(it.name, it.incoming.resolutionResult.rootComponent) }
    }
}

configurations.configureEach { resolutionStrategy.failOnNonReproducibleResolution() }

apply(plugin = "base")
tasks.named("check") { dependsOn(checkResolvedVersions) }

if (isRoot) {
    // 標準の dependencyInsight は buildscript の classpath を指定できない
    tasks.register<org.gradle.api.tasks.diagnostics.DependencyInsightReportTask>("resolvedPluginInsight") {
        setConfiguration(project.buildscript.configurations.getByName("classpath"))
        showingAllVariants.set(false)
    }
}
