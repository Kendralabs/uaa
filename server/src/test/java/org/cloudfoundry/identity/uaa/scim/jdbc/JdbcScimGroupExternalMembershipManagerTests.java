/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2016] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.scim.jdbc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.cloudfoundry.identity.uaa.constants.OriginKeys;
import org.cloudfoundry.identity.uaa.resources.jdbc.JdbcPagingListFactory;
import org.cloudfoundry.identity.uaa.scim.ScimGroup;
import org.cloudfoundry.identity.uaa.scim.ScimGroupExternalMember;
import org.cloudfoundry.identity.uaa.scim.exception.ScimResourceNotFoundException;
import org.cloudfoundry.identity.uaa.scim.test.TestUtils;
import org.cloudfoundry.identity.uaa.test.JdbcTestBase;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.JdbcIdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;
import org.junit.Before;
import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;

public class JdbcScimGroupExternalMembershipManagerTests extends JdbcTestBase {

    private JdbcScimGroupProvisioning gdao;

    private JdbcScimGroupExternalMembershipManager edao;

    private static final String addGroupSqlFormat = "insert into groups (id, displayName, identity_zone_id) values ('%s','%s','%s')";

    private String origin = OriginKeys.LDAP;

    private IdentityZone otherZone;

    @Before
    public void initJdbcScimGroupExternalMembershipManagerTests() {

        String zoneId = new RandomValueStringGenerator().generate();
        otherZone = MultitenancyFixture.identityZone(zoneId,zoneId);
        otherZone = new JdbcIdentityZoneProvisioning(jdbcTemplate).create(otherZone);

        JdbcTemplate template = new JdbcTemplate(dataSource);

        JdbcPagingListFactory pagingListFactory = new JdbcPagingListFactory(template, limitSqlAdapter);
        gdao = new JdbcScimGroupProvisioning(template, pagingListFactory);

        edao = new JdbcScimGroupExternalMembershipManager(template, pagingListFactory);
        edao.setScimGroupProvisioning(gdao);

        for (IdentityZone zone : Arrays.asList(IdentityZone.getUaa(), otherZone)) {
            IdentityZoneHolder.set(zone);
            addGroup("g1-"+zone.getId(), "test1");
            addGroup("g2-"+zone.getId(), "test2");
            addGroup("g3-"+zone.getId(), "test3");
            IdentityZoneHolder.clear();
        }


        validateCount(0);
    }

    private void addGroup(String id, String name) {
        TestUtils.assertNoSuchUser(jdbcTemplate, "id", id);
        jdbcTemplate.execute(String.format(addGroupSqlFormat, id, name, IdentityZoneHolder.get().getId()));
    }

    private void validateCount(int expected) {
        int existingMemberCount = jdbcTemplate.queryForObject("select count(*) from external_group_mapping", Integer.class);
        assertEquals(expected, existingMemberCount);
    }

    @Test
    public void addExternalMappingToGroup() {
        createGroupMapping();
    }

    private void createGroupMapping() {
        ScimGroup group = gdao.retrieve("g1-"+IdentityZoneHolder.get().getId());
        assertNotNull(group);

        ScimGroupExternalMember member = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "cn=engineering,ou=groups,dc=example,dc=com", origin);
        assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
        assertEquals(member.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");

        List<ScimGroupExternalMember> externalMapping = edao.getExternalGroupMapsByGroupId("g1-"+IdentityZoneHolder.get().getId(), origin);

        assertEquals(externalMapping.size(), 1);
    }

    @Test(expected = ScimResourceNotFoundException.class)
    public void cannot_Retrieve_ById_For_OtherZone() {
        edao.getExternalGroupMapsByGroupId("g1-"+otherZone.getId(), origin);
    }

    @Test(expected = ScimResourceNotFoundException.class)
    public void cannot_Map_ById_For_OtherZone() {
        edao.mapExternalGroup("g1-" + otherZone.getId(), "CN=engineering,OU=groups,DC=example,DC=com", origin);
    }

    @Test
    public void using_filter_query_filters_by_zone() {
        map3GroupsInEachZone();
        assertEquals(3, edao.query("").size());
        assertEquals(3, edao.query("externalGroup sw \"cn\"").size());
        assertEquals(3, edao.query("group_id sw \"g\"").size());
        assertEquals(0, edao.query("origin eq \""+ OriginKeys.UAA+"\"").size());
        assertEquals(3, edao.query("origin eq \""+ OriginKeys.LDAP+"\"").size());
    }

    @Test
    public void using_filter_delete_filters_by_zone() {
        map3GroupsInEachZone();
        assertEquals(3, edao.query("").size());
        edao.delete("");
        assertEquals(0, edao.query("").size());
        assertThat(jdbcTemplate.queryForObject("select count(*) from external_group_mapping", Integer.class), is(3));

        map3GroupsInEachZone();
        assertEquals(3, edao.query("").size());
        edao.delete("origin eq \""+ OriginKeys.LDAP+"\"");
        assertEquals(0, edao.query("").size());
        assertThat(jdbcTemplate.queryForObject("select count(*) from external_group_mapping", Integer.class), is(3));

        map3GroupsInEachZone();
        assertEquals(3, edao.query("").size());
        edao.delete("origin eq \""+ OriginKeys.UAA+"\"");
        assertEquals(3, edao.query("").size());
        assertThat(jdbcTemplate.queryForObject("select count(*) from external_group_mapping", Integer.class), is(6));
    }


    protected void map3GroupsInEachZone() {
        for (IdentityZone zone : Arrays.asList(IdentityZone.getUaa(), otherZone)) {
            IdentityZoneHolder.set(zone);

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g1-" + IdentityZoneHolder.get().getId(), "cn=engineering,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g1-" + IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");
            }

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g1-" + IdentityZoneHolder.get().getId(), "cn=hr,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g1-" + IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=hr,ou=groups,dc=example,dc=com");
            }

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g1-" + IdentityZoneHolder.get().getId(), "cn=mgmt,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g1-" + IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=mgmt,ou=groups,dc=example,dc=com");
            }
            IdentityZoneHolder.clear();
        }
    }

    @Test
    public void adding_ExternalMappingToGroup_IsCaseInsensitive() throws Exception {
        createGroupMapping();
        ScimGroupExternalMember member = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "CN=engineering,OU=groups,DC=example,DC=com", origin);
        assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
        assertEquals(member.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");
        List<ScimGroupExternalMember> externalMapping = edao.getExternalGroupMapsByGroupId("g1-"+IdentityZoneHolder.get().getId(), origin);
        assertEquals(externalMapping.size(), 1);
    }

    @Test
    public void addExternalMappingToGroupThatAlreadyExists() {
        createGroupMapping();

        ScimGroupExternalMember dupMember = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "cn=engineering,ou=groups,dc=example,dc=com", origin);
        assertEquals(dupMember.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
        assertEquals(dupMember.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");
    }

    @Test
    public void addMultipleExternalMappingsToGroup() {
        ScimGroup group = gdao.retrieve("g1-"+IdentityZoneHolder.get().getId());
        assertNotNull(group);

        {
            ScimGroupExternalMember member = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "cn=engineering,ou=groups,dc=example,dc=com", origin);
            assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
            assertEquals(member.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");
        }

        {
            ScimGroupExternalMember member = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "cn=hr,ou=groups,dc=example,dc=com", origin);
            assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
            assertEquals(member.getExternalGroup(), "cn=hr,ou=groups,dc=example,dc=com");
        }

        {
            ScimGroupExternalMember member = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "cn=mgmt,ou=groups,dc=example,dc=com", origin);
            assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
            assertEquals(member.getExternalGroup(), "cn=mgmt,ou=groups,dc=example,dc=com");
        }

        List<ScimGroupExternalMember> externalMappings = edao.getExternalGroupMapsByGroupId("g1-"+IdentityZoneHolder.get().getId(), origin);
        assertEquals(externalMappings.size(), 3);

        List<String> testGroups = new ArrayList<>(
            Arrays.asList(
                new String[] {
                    "cn=engineering,ou=groups,dc=example,dc=com",
                    "cn=hr,ou=groups,dc=example,dc=com",
                    "cn=mgmt,ou=groups,dc=example,dc=com"
                }
            )
        );
        for (ScimGroupExternalMember member : externalMappings) {
            assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
            assertNotNull(testGroups.remove(member.getExternalGroup()));
        }

        assertEquals(testGroups.size(), 0);
    }

    @Test
    public void addMultipleExternalMappingsToMultipleGroup() {
        {
            ScimGroup group = gdao.retrieve("g1-"+IdentityZoneHolder.get().getId());
            assertNotNull(group);

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "cn=engineering,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");
            }

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "cn=HR,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=hr,ou=groups,dc=example,dc=com");
            }

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g1-"+IdentityZoneHolder.get().getId(), "cn=mgmt,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g1-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=mgmt,ou=groups,dc=example,dc=com");
            }
            List<ScimGroupExternalMember> externalMappings = edao.getExternalGroupMapsByGroupId("g1-"+IdentityZoneHolder.get().getId(), origin);
            assertEquals(externalMappings.size(), 3);
        }
        {
            ScimGroup group = gdao.retrieve("g2-"+IdentityZoneHolder.get().getId());
            assertNotNull(group);

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g2-"+IdentityZoneHolder.get().getId(), "cn=engineering,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g2-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");
            }

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g2-"+IdentityZoneHolder.get().getId(), "cn=HR,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g2-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=hr,ou=groups,dc=example,dc=com");
            }

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g2-"+IdentityZoneHolder.get().getId(), "cn=mgmt,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g2-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=mgmt,ou=groups,dc=example,dc=com");
            }
            List<ScimGroupExternalMember> externalMappings = edao.getExternalGroupMapsByGroupId("g2-"+IdentityZoneHolder.get().getId(), origin);
            assertEquals(externalMappings.size(), 3);
        }
        {
            ScimGroup group = gdao.retrieve("g3-"+IdentityZoneHolder.get().getId());
            assertNotNull(group);

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g3-"+IdentityZoneHolder.get().getId(), "cn=engineering,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g3-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");
            }

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g3-"+IdentityZoneHolder.get().getId(), "cn=HR,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g3-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=hr,ou=groups,dc=example,dc=com");
            }

            {
                ScimGroupExternalMember member = edao.mapExternalGroup("g3-"+IdentityZoneHolder.get().getId(), "cn=mgmt,ou=groups,dc=example,dc=com", origin);
                assertEquals(member.getGroupId(), "g3-"+IdentityZoneHolder.get().getId());
                assertEquals(member.getExternalGroup(), "cn=mgmt,ou=groups,dc=example,dc=com");
            }
            List<ScimGroupExternalMember> externalMappings = edao.getExternalGroupMapsByGroupId("g3-"+IdentityZoneHolder.get().getId(), origin);
            assertEquals(externalMappings.size(), 3);
        }

        List<String> testGroups = new ArrayList<>(Arrays.asList(new String[] { "g1-"+IdentityZoneHolder.get().getId(), "g2-"+IdentityZoneHolder.get().getId(), "g3-"+IdentityZoneHolder.get().getId() }));

        {
            // LDAP groups are case preserving on fetch and case insensitive on
            // search
            List<ScimGroupExternalMember> externalMappings = edao
                .getExternalGroupMapsByExternalGroup("cn=engineering,ou=groups,dc=example,dc=com", origin);
            for (ScimGroupExternalMember member : externalMappings) {
                assertEquals(member.getExternalGroup(), "cn=engineering,ou=groups,dc=example,dc=com");
                assertNotNull(testGroups.remove(member.getGroupId()));
            }

            assertEquals(testGroups.size(), 0);
        }

        {
            // LDAP groups are case preserving on fetch and case insensitive on
            // search
            List<ScimGroupExternalMember> externalMappings = edao
                            .getExternalGroupMapsByExternalGroup("cn=hr,ou=groups,dc=example,dc=com", origin);
            for (ScimGroupExternalMember member : externalMappings) {
                assertEquals(member.getExternalGroup(), "cn=hr,ou=groups,dc=example,dc=com");
                assertNotNull(testGroups.remove(member.getGroupId()));
            }

            assertEquals(testGroups.size(), 0);

            List<ScimGroupExternalMember> externalMappings2 = edao
                            .getExternalGroupMapsByExternalGroup("cn=HR,ou=groups,dc=example,dc=com", origin);
            for (ScimGroupExternalMember member : externalMappings2) {
                assertEquals(member.getExternalGroup(), "cn=hr,ou=groups,dc=example,dc=com");
                assertNotNull(testGroups.remove(member.getGroupId()));
            }

            assertEquals(testGroups.size(), 0);
        }

        {
            // LDAP groups are case preserving on fetch and case insensitive on
            // search
            List<ScimGroupExternalMember> externalMappings = edao
                            .getExternalGroupMapsByExternalGroup("cn=mgmt,ou=groups,dc=example,dc=com", origin);
            for (ScimGroupExternalMember member : externalMappings) {
                assertEquals(member.getExternalGroup(), "cn=mgmt,ou=groups,dc=example,dc=com");
                assertNotNull(testGroups.remove(member.getGroupId()));
            }

            assertEquals(testGroups.size(), 0);
        }
    }
}
