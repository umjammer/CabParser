/*
 * Copyright 2018 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.nio.file.Paths
import java.time.Instant

buildscript {
    // load properties from custom location
    def propsFile = Paths.get("${projectDir}/../../gradle.properties").normalize().toFile()
    if (propsFile.canRead()) {
        println("Loading custom property data from: ${propsFile}")

        def props = new Properties()
        propsFile.withInputStream {props.load(it)}
        props.each {key, val -> project.ext.set(key, val)}
    }
    else {
        ext.sonatypeUsername = ""
        ext.sonatypePassword = ""
    }

    // for plugin publishing and license sources
    repositories {
        maven {url "https://plugins.gradle.org/m2/"}
    }
    dependencies {
        // this is the only way to also get the source code for IDE auto-complete
        classpath "gradle.plugin.com.dorkbox:Licensing:1.2.2"
        classpath "gradle.plugin.com.dorkbox:Licensing:1.2.2:sources"
    }
}

plugins {
    id 'java'
    id 'maven-publish'
    id 'signing'

    // close and release on sonatype
    id 'io.codearte.nexus-staging' version '0.11.0'

    id "com.dorkbox.CrossCompile" version "1.0.1"
    id "com.dorkbox.VersionUpdate" version "1.2"

    // setup checking for the latest version of a plugin or dependency (and updating the gradle build)
    id "se.patrikerdes.use-latest-versions" version "0.2.3"
    id 'com.github.ben-manes.versions' version '0.16.0'
}

// this is the only way to also get the source code for IDE auto-complete
apply plugin: "com.dorkbox.Licensing"

// give us access to api/implementation differences for building java libraries
apply plugin: 'java-library'


project.description = 'Parse and extract data from Microsoft LZX compressed .cab files for Java 6+'
project.group = 'com.dorkbox'
project.version = '2.15'

project.ext.name = 'CabParser'
project.ext.url = 'https://git.dorkbox.com/dorkbox/CabParser'


sourceCompatibility = 1.6
targetCompatibility = 1.6


licensing {
    license(License.APACHE_2) {
        author 'dorkbox, llc'
        url project.ext.url
        note project.description
    }

    license('Dorkbox Utils', License.APACHE_2) {
        author 'dorkbox, llc'
        url 'https://git.dorkbox.com/dorkbox/Utilities'
    }

    license('jOOU - Unsigned Numbers for Java', License.APACHE_2) {
        copyright 2013
        author 'Lukas Eder, lukas.eder@gmail.com'
        url 'https://github.com/jOOQ/jOOU'
    }
}


sourceSets {
    main {
        java {
            setSrcDirs Collections.singletonList('src')
        }
    }
}


repositories {
    mavenLocal() // this must be first!
    jcenter()
}


dependencies {
    implementation(project('Utilities')) {
        // don't include any of the project dependencies for anything
        transitive = false
    }

    // our main dependencies are ALSO the same as the limited utilities (they are not automatically pulled in from other sourceSets)
    // needed by the utilities (custom since we don't want to include everything). IntelliJ includes everything, but our builds do not
}

project('Utilities') {
    tasks.withType(Test) {
        // want to remove utilities project from unit tests. It's unnecessary to run unit tests for the entire Utilities project
        exclude('**/*')
    }

    tasks.withType(JavaCompile) {
        options.encoding = 'UTF-8'
        options.incremental = true

        // setup compile options. we specifically want to suppress usage of "Unsafe"
        options.compilerArgs = ['-XDignore.symbol.file', '-Xlint:deprecation']
    }
}

///////////////////////////////
//////    Task defaults
///////////////////////////////
tasks.withType(JavaCompile) {
    options.encoding = 'UTF-8'
}

tasks.withType(Jar) {
    duplicatesStrategy DuplicatesStrategy.FAIL

    manifest {
        attributes['Implementation-Version'] = version
        attributes['Build-Date'] = Instant.now().toString()
    }
}

///////////////////////////////
//////    UTILITIES COMPILE (for inclusion into jars)
///////////////////////////////
static String[] javaFile(String... fileNames) {
    def fileList = [] as ArrayList

    for (name in fileNames) {
        def fixed = name.replace('.', '/') + '.java'
        fileList.add(fixed)
    }

    return fileList
}

task compileUtils(type: JavaCompile) {
    // we don't want the default include of **/*.java
    getIncludes().clear()

    source = Collections.singletonList('../Utilities/src')
    include javaFile('dorkbox.util.bytes.LittleEndian',
                     'dorkbox.util.bytes.UByte',
                     'dorkbox.util.bytes.UInteger',
                     'dorkbox.util.bytes.ULong',
                     'dorkbox.util.bytes.Unsigned',
                     'dorkbox.util.bytes.UNumber',
                     'dorkbox.util.bytes.UShort')

    classpath = sourceSets.main.compileClasspath
    destinationDir = file("$rootDir/build/classes_utilities")
}

///////////////////////////////
//////    Jar Tasks
///////////////////////////////
jar {
    dependsOn compileUtils

    // include applicable class files from subset of Utilities project
    from compileUtils.destinationDir
}


/////////////////////////////
////    Maven Publishing + Release
/////////////////////////////
task sourceJar(type: Jar) {
    description = "Creates a JAR that contains the source code."

    from sourceSets.main.java
    from compileUtils.source

    classifier = "sources"
}

task javaDocJar(type: Jar) {
    description = "Creates a JAR that contains the javadocs."

    classifier = "javadoc"
}

// for testing, we don't publish to maven central, but only to local maven
publishing {
    publications {
        maven(MavenPublication) {
            from components.java

            artifact(javaDocJar)
            artifact(sourceJar)

            groupId project.group
            artifactId project.ext.name
            version project.version

            pom {
                withXml {
                    // eliminate logback and utilities (no need in maven POMs)
                    def root = asNode()

                    root.dependencies.'*'.findAll() {
                        it.artifactId.text() == "Utilities"
                    }.each() {
                        it.parent().remove(it)
                    }
                }

                name = project.ext.name
                url = project.ext.url
                description = project.description

                issueManagement {
                    url = "${project.ext.url}/issues".toString()
                    system = 'Gitea Issues'
                }

                organization {
                    name = 'dorkbox, llc'
                    url = 'https://dorkbox.com'
                }

                developers {
                    developer {
                        name = 'dorkbox, llc'
                        email = 'email@dorkbox.com'
                    }
                }

                scm {
                    url = project.ext.url
                    connection = "scm:${project.ext.url}.git".toString()
                }
            }
        }
    }

    repositories {
        maven {
            url "https://oss.sonatype.org/service/local/staging/deploy/maven2"
            credentials {
//                username sonatypeUsername
//                password sonatypePassword
            }
        }
    }
}

nexusStaging {
//    username sonatypeUsername
//    password sonatypePassword
}

signing {
    sign publishing.publications.maven
}

// output the release URL in the console
releaseRepository.doLast {
    def URL = 'https://oss.sonatype.org/content/repositories/releases/'
    def projectName = project.group.toString().replaceAll('\\.', '/')
    def name = project.ext.name
    def version = project.version

    println("Maven URL: ${URL}${projectName}/${name}/${version}/")
}

// we don't use maven with the plugin (it's uploaded separately to gradle plugins)
tasks.withType(PublishToMavenRepository) {
    onlyIf {
        repository == publishing.repositories.maven && publication == publishing.publications.maven
    }
}
tasks.withType(PublishToMavenLocal) {
    onlyIf {
        publication == publishing.publications.maven
    }
}

/////////////////////////////
////    Gradle Wrapper Configuration.
///  Run this task, then refresh the gradle project
/////////////////////////////
task updateWrapper(type: Wrapper) {
    gradleVersion = '4.10.2'
    distributionUrl = distributionUrl.replace("bin", "all")
    setDistributionType(Wrapper.DistributionType.ALL)
}
