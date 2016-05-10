package com.netflix.nebula.lint.rule.dependency

import com.netflix.nebula.lint.TestKitSpecification
import org.gradle.api.Project
import org.gradle.api.internal.artifacts.DefaultModuleVersionIdentifier
import org.gradle.api.internal.artifacts.DefaultResolvedDependency
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Unroll

class DependencyServiceSpec extends TestKitSpecification {
    Project project

    def setup() {
        project = ProjectBuilder.builder().withName('dependency-service').withProjectDir(projectDir).build()
        project.with {
            apply plugin: 'java'
            repositories { mavenCentral() }
        }
    }

    def 'transitive dependencies with a cycle'() {
        setup:
        def service = DependencyService.forProject(project)

        def resolvedDependency = { String dep ->
            def (group, name, version) = dep.split(':')
            new DefaultResolvedDependency(new DefaultModuleVersionIdentifier(group, name, version), 'compile')
        }

        when:
        def a1 = resolvedDependency('a:a:1')
        def b1 = resolvedDependency('b:b:1')
        a1.children.add(b1)
        b1.children.add(a1)

        def transitives = service.transitiveDependencies(a1)

        then:
        transitives == [b1] as Set
    }

    @Unroll
    def 'find unused dependencies'() {
        when:
        buildFile.text = """\
            import com.netflix.nebula.lint.rule.dependency.*

            plugins {
                id 'nebula.lint'
                id 'java'
            }

            repositories { mavenCentral() }
            dependencies {
                compile 'com.google.inject.extensions:guice-servlet:3.0' // used directly
                compile 'javax.servlet:servlet-api:2.5' // used indirectly through guice-servlet
                compile 'commons-lang:commons-lang:2.6' // unused
                testCompile 'junit:junit:4.11'
                testCompile 'commons-lang:commons-lang:2.6' // unused
            }

            // a task to generate an unused dependency report for each configuration
            project.configurations.collect { it.name }.each { conf ->
                task "\${conf}Unused"(dependsOn: compileTestJava) << {
                  new File(projectDir, "\${conf}Unused.txt").text = DependencyService.forProject(project)
                    .unusedDependencies(conf)
                    .join('\\n')
                }
            }
            """.stripMargin()

        createJavaSourceFile('''\
            import com.google.inject.servlet.*;
            public abstract class Main extends GuiceServletContextListener { }
        ''')

        createJavaTestFile('''\
            import org.junit.*;
            public class MyTest {
                @Test public void test() {}
            }
        ''')

        then:
        runTasksSuccessfully('compileUnused')
        new File(projectDir, 'compileUnused.txt').readLines() == ['commons-lang:commons-lang:2.6']

        then:
        runTasksSuccessfully('testCompileUnused')
        new File(projectDir, 'testCompileUnused.txt').readLines() == ['commons-lang:commons-lang:2.6']
    }

    @Unroll
    def 'find undeclared dependencies'() {
        when:
        buildFile.text = """\
            import com.netflix.nebula.lint.rule.dependency.*

            plugins {
                id 'nebula.lint'
                id 'java'
            }

            repositories { mavenCentral() }
            dependencies {
                compile 'io.springfox:springfox-core:2.0.2'
            }

            // a task to generate an undeclared dependency report for each configuration
            project.configurations.collect { it.name }.each { conf ->
                task "\${conf}Undeclared"(dependsOn: compileTestJava) << {
                  new File(projectDir, "\${conf}Undeclared.txt").text = DependencyService.forProject(project)
                    .undeclaredDependencies(conf)
                    .join('\\n')
                }
            }
            """.stripMargin()

        createJavaSourceFile('''\
            import com.google.common.collect.*;
            public class Main { Multimap m = HashMultimap.create(); }
        ''')

        then:
        runTasksSuccessfully('compileUndeclared')
        new File(projectDir, 'compileUndeclared.txt').readLines() == ['com.google.guava:guava:18.0']
    }

    def 'first level dependencies in conf'() {
        when:
        project.with {
            dependencies {
                compile 'com.google.guava:guava:18.0'
                testCompile 'junit:junit:latest.release'
            }
        }

        def deps = DependencyService.forProject(project).firstLevelDependenciesInConf(project.configurations.testCompile)

        project.configurations.compile.incoming.afterResolve {
            project.configurations.compile.incoming.resolutionResult.root.dependencies
        }

        then:
        deps.size() == 1
        deps[0].module.toString() == 'junit:junit'
        deps[0].version != 'latest.release' // the version has been resolved to a fixed version
    }

    def 'find the nearest source set to a configuration'() {
        when:
        project.with {
            apply plugin: 'war' // to define the providedCompile conf
            configurations {
                deeper
                deep { extendsFrom deeper }
            }
            configurations.compile { extendsFrom configurations.deep }
        }

        def service = DependencyService.forProject(project)

        then:
        service.sourceSetByConf('compile')?.name == 'main'
        service.sourceSetByConf('providedCompile')?.name == 'main'
        service.sourceSetByConf('deeper')?.name == 'main'
    }

    def 'identify configurations used at runtime (not in the compile scope of one of the project\'s source sets)'() {
        when:
        def service = DependencyService.forProject(project)

        def troubleConf = project.configurations.create('trouble')

        project.configurations.compile.extendsFrom(troubleConf)
        project.configurations.runtime.extendsFrom(troubleConf)

        then:
        !service.isRuntime('compile')
        service.isRuntime('runtime')
        service.isRuntime('trouble')
    }

    def 'identify parent source sets'() {
        expect:
        DependencyService.forProject(project).parentSourceSetConfigurations('compile')*.name == ['testCompile']
    }
}