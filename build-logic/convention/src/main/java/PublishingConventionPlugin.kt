/*
 * Copyright 2025 Harry Timothy Tumalewa
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SonatypeHost
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class PublishingConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target) {
            group = "io.github.harrytmthy"
            version = "1.3.0-alpha02"

            pluginManager.apply("org.jetbrains.dokka")
            pluginManager.apply("com.vanniktech.maven.publish")
            extensions.configure<MavenPublishBaseExtension> {
                publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
                signAllPublications()
                pom {
                    url.set("https://github.com/harrytmthy/safebox")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("harrytmthy")
                            name.set("Harry Timothy Tumalewa")
                            email.set("harrytmthy@gmail.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/harrytmthy/safebox.git")
                        developerConnection.set("scm:git:ssh://github.com:harrytmthy/safebox.git")
                        url.set("https://github.com/harrytmthy/safebox")
                    }
                }
            }
        }
    }
}