/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.projectmanipulator.npm;

import org.jboss.projectmanipulator.core.ManipulationException;
import org.jboss.projectmanipulator.core.ManipulationSession;
import org.jboss.projectmanipulator.core.Manipulator;
import org.jboss.projectmanipulator.core.Project;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.isNumeric;
import static org.apache.commons.lang.StringUtils.leftPad;
import static org.apache.commons.lang.StringUtils.substring;
import static org.apache.commons.lang.math.NumberUtils.createInteger;

/**
 * {@link Manipulator} implementation that can modify an NPM project's version with either static
 * or calculated, incremental version qualifier.
 */
public class NpmPackageVersionManipulator implements Manipulator {

    /** The separator that's used between the original version and the generated or provided suffix. */
    public static final String SUFFIX_SEPARATOR = "-";

    /** The separator that's used between the suffix and the generated build number. */
    public static final String SUFFIX_INCREMENT_SEPARATOR = "-";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private String versionIncrementalSuffix;

    private Integer versionIncrementalSuffixPadding;

    private String restUrl;

    private String versionSuffixOverride;

    private String versionOverride;

    private ManipulationSession session;

    private List<Class<? extends Manipulator>> dependencies;


    @Override
    public boolean init(ManipulationSession session) throws ManipulationException {
        this.session = session;

        Properties userProps = session.getUserProps();
        if (userProps != null) {
            versionOverride = userProps.getProperty("versionOverride");
            versionSuffixOverride = userProps.getProperty("versionSuffixOverride");
            restUrl = userProps.getProperty("restURL");
            versionIncrementalSuffix = userProps.getProperty("versionIncrementalSuffix");
            try {
                versionIncrementalSuffixPadding = createInteger(userProps.getProperty("versionIncrementalSuffixPadding"));
            } catch (NumberFormatException ex) {
                logger.warn("Invalid number provided in versionIncrementalSuffixPadding \'"
                        + userProps.getProperty("versionIncrementalSuffixPadding") + "\'. Using 1.");
                logger.debug("Error was: {}", ex.getMessage(), ex);
            }
            if (versionIncrementalSuffixPadding == null) {
                versionIncrementalSuffixPadding = 1;
            }

            return !isEmpty(versionOverride)
                    || !isEmpty(versionSuffixOverride)
                    || !isEmpty(restUrl) && !isEmpty(versionIncrementalSuffix);
        }

        return false;
    }

    @Override
    public Set<Project> applyChanges(List<Project> projects) throws ManipulationException {
        @SuppressWarnings("unchecked")
        Map<String, Set<String>> availableVersions = session.getState(DAVersionsCollector.AVAILABLE_VERSIONS, Map.class);

        Set<Project> changed = new HashSet<>();
        for (Project project : projects) {
            if (project instanceof NpmPackage) {
                NpmPackage npmPackage = (NpmPackage) project;

                String origVersion = npmPackage.getVersion();
                String newVersion = null;
                if (isEmpty(versionOverride)) {
                    if (isEmpty(versionSuffixOverride)) {
                        int suffixNum = findHighestIncrementalNum(origVersion,
                                availableVersions.get(npmPackage.getName())) + 1;
                        String versionSuffix = versionIncrementalSuffix + SUFFIX_INCREMENT_SEPARATOR
                                + leftPad(String.valueOf(suffixNum), versionIncrementalSuffixPadding, '0');
                        newVersion = origVersion + SUFFIX_SEPARATOR + versionSuffix;
                    } else {
                        newVersion = origVersion + SUFFIX_SEPARATOR + versionSuffixOverride;
                    }
                } else {
                    newVersion = versionOverride;
                }

                if (!origVersion.equals(newVersion)) {
                    npmPackage.setVersion(newVersion);
                    changed.add(npmPackage);
                }
            } else {
                throw new ManipulationException(
                        "Manipulation failed, because project type %s is not supported by NPM manipulation.", null,
                        project.getClass());
            }
        }
        return changed;
    }

    private int findHighestIncrementalNum(String origVersion, Set<String> availableVersions) {
        String lookupPrefix = origVersion + SUFFIX_SEPARATOR + versionIncrementalSuffix;
        int highestFoundNum = 0;
//        if (existingVersions != null) {
            for (String version : availableVersions) {
                if (version.startsWith(lookupPrefix)) {
                    String incrementalPart = substring(version, lookupPrefix.length() + 1);
                    if (isNumeric(incrementalPart)) {
                        int foundNum = Integer.valueOf(incrementalPart);
                        if (foundNum > highestFoundNum) {
                            highestFoundNum = foundNum;
                        }
                    }
                }
            }
//		}
        return highestFoundNum;
    }

    @Override
    public Collection<Class<? extends Manipulator>> getDependencies() {
        if (dependencies == null) {
            dependencies = new ArrayList<>();
            if (isEmpty(versionOverride) && !isEmpty(restUrl)) {
                dependencies.add(DAVersionsCollector.class);
            }
        }
        return dependencies;
    }

}