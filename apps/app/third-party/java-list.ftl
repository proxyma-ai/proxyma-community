<#--
  The Java section of THIRD-PARTY-NOTICES.txt - the template license-maven-plugin's add-third-party
  fills in (configured in apps/app/backend/pom.xml). One block per library, written to read in a
  proportional font: the Settings dialog shows this file in the UI face, where columns would not
  line up.
-->
<#function displayName p>
  <#if p.name?? && p.name?index_of('Unnamed') == -1 && p.name?trim != ''>
    <#return p.name?trim>
  <#else>
    <#return p.artifactId>
  </#if>
</#function>
JAVA LIBRARIES
==============

The application jar carries ${dependencyMap?size} third-party Java libraries (BOOT-INF/lib in the jar).
Each is listed with its Maven coordinates, its project page and its license, as its own
published POM states it, with each license's many spellings merged into one name. Where a
library offers a choice of licenses, every choice is named.

<#list dependencyMap as e>
<#assign p = e.getKey()/>
<#assign licenses = e.getValue()/>
${displayName(p)}
  ${p.groupId}:${p.artifactId} ${p.version}
<#if p.url?? && p.url?trim != ''>
  ${p.url?trim}
</#if>
  License: <#list licenses as l>${l}<#sep> / </#sep></#list>

</#list>
