package com.siheungbootcamp.teamd.cicd

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** I2 배포 파일이 WIF·IAP·SHA 이미지·자동 롤백 계약을 계속 지키는지 검사한다. */
class CicdConfigurationTest {
    private val root: Path = Path.of("").toAbsolutePath()

    @Test
    fun `PR 워크플로는 배포 없이 빌드와 테스트 결과 업로드만 수행한다`() {
        val workflow = read(".github/workflows/ci.yml")

        assertContains(workflow, "pull_request:")
        assertContains(workflow, "branches: [main, develop]")
        assertContains(workflow, "./gradlew build")
        assertContains(workflow, "actions/upload-artifact@")
        assertFalse(workflow.contains("gcloud compute"))
        assertFalse(workflow.contains("docker push"))
    }

    @Test
    fun `배포 워크플로는 WIF와 IAP를 사용해 SHA 이미지를 배포한다`() {
        val workflow = read(".github/workflows/cd.yml")

        assertContains(workflow, "id-token: write")
        assertContains(workflow, "google-github-actions/auth@")
        assertContains(workflow, "workload_identity_provider:")
        assertContains(workflow, "docker push")
        assertContains(workflow, "${'$'}{{ github.sha }}")
        assertContains(workflow, "IMAGE_REPOSITORY:")
        assertContains(workflow, "'${'$'}{IMAGE_REPOSITORY}'")
        assertContains(workflow, "--tunnel-through-iap")
        assertContains(workflow, "gcloud sql backups list")
        assertFalse(workflow.contains("credentials_json"))
        assertFalse(workflow.contains("service_account_key"))
    }

    @Test
    fun `VM 배포 스크립트는 health와 smoke 실패 시 직전 SHA로 롤백한다`() {
        val deploy = read("scripts/deploy.sh")

        assertContains(deploy, "current_sha")
        assertContains(deploy, "previous_sha")
        assertContains(deploy, "rollback")
        assertContains(deploy, "/actuator/health")
        assertContains(deploy, "smoke-test.sh")
        assertContains(deploy, "chmod 600")
        assertFalse(deploy.contains("project-f70dd7ef-e577-4b2a-bbd"))
    }

    @Test
    fun `운영 배포 경로는 실제 도메인 설정을 전달하고 sslip 임시 도메인을 포함하지 않는다`() {
        val workflow = read(".github/workflows/cd.yml")
        val deploy = read("scripts/deploy.sh")
        val deployTest = read("scripts/tests/deploy-test.sh")
        val envExample = read(".env.example")

        assertContains(workflow, "API_DOMAIN: ${'$'}{{ vars.API_DOMAIN }}")
        assertContains(workflow, "FRONTEND_BASE_URL: ${'$'}{{ vars.FRONTEND_BASE_URL }}")
        assertContains(workflow, "CORS_ALLOWED_ORIGINS: ${'$'}{{ vars.CORS_ALLOWED_ORIGINS }}")
        assertContains(workflow, "'${'$'}{API_DOMAIN}' '${'$'}{FRONTEND_BASE_URL}' '${'$'}{CORS_ALLOWED_ORIGINS}'")
        assertContains(workflow, "scripts/deploy.sh scripts/smoke-test.sh dynamic.yml.template")
        assertContains(deploy, "API_DOMAIN")
        assertContains(deploy, "dynamic.yml.template")
        assertContains(envExample, "API_DOMAIN=\n")
        assertContains(envExample, "FRONTEND_BASE_URL=\n")
        assertContains(envExample, "CORS_ALLOWED_ORIGINS=\n")
        assertFalse(envExample.contains("api.yeondang.com"))
        assertFalse(envExample.contains("https://yeondang.com"))
        assertFalse(listOf(workflow, deploy, envExample).any { it.contains("sslip.io") })
        assertContains(deployTest, "CORS_ALLOWED_ORIGIN_PATTERNS=https://team-d-*.vercel.app")
    }

    @Test
    fun `smoke test는 생성 조회 잘못된 토큰 흐름을 검증한다`() {
        val smoke = read("scripts/smoke-test.sh")

        assertContains(smoke, "/actuator/health")
        assertContains(smoke, "POST")
        assertContains(smoke, "/api/v1/boards")
        assertContains(smoke, "Authorization: Bearer")
        assertContains(smoke, "401")
    }

    @Test
    fun `smoke test 날짜는 서울 기준으로 내일부터 8일 후까지다`() {
        val smoke = read("scripts/smoke-test.sh")

        assertContains(smoke, "TZ=Asia/Seoul")
        assertContains(smoke, "+1 day")
        assertContains(smoke, "+8 days")
    }

    @Test
    fun `migration 안전 규칙은 적용 파일 불변과 expand contract를 명시한다`() {
        val rules = read("docs/deployment/migration-safety.md")

        assertContains(rules, "적용된 migration 파일 수정 금지")
        assertContains(rules, "컬럼 추가")
        assertContains(rules, "코드 전환")
        assertContains(rules, "구 컬럼 제거")
        assertContains(rules, "이전 코드")
    }

    @Test
    fun `필수 파일 누락 메시지는 실제 상대 경로를 표시한다`() {
        val missingPath = "missing/i2-file.yml"

        val error = assertFailsWith<AssertionError> { read(missingPath) }

        assertContains(error.message.orEmpty(), missingPath)
    }

    private fun read(relativePath: String): String {
        val path = root.resolve(relativePath)
        assertTrue(Files.isRegularFile(path), "필수 I2 파일이 없습니다: $relativePath")
        return path.readText()
    }
}
