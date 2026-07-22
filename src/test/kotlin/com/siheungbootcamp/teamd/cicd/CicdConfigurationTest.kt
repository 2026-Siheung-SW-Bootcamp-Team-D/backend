package com.siheungbootcamp.teamd.cicd

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** I2 배포 파일이 WIF·IAP·SHA 이미지·자동 롤백 계약을 계속 지키는지 검사한다. */
class CicdConfigurationTest {
    private val root: Path = Path.of("").toAbsolutePath()

    @Test
    fun `PR 워크플로는 배포 없이 빌드와 테스트 결과 업로드만 수행한다`() {
        val workflow = read(".github/workflows/pr.yml")

        assertContains(workflow, "pull_request:")
        assertContains(workflow, "./gradlew build")
        assertContains(workflow, "actions/upload-artifact@")
        assertFalse(workflow.contains("gcloud compute"))
        assertFalse(workflow.contains("docker push"))
    }

    @Test
    fun `배포 워크플로는 WIF와 IAP를 사용해 SHA 이미지를 배포한다`() {
        val workflow = read(".github/workflows/deploy.yml")

        assertContains(workflow, "id-token: write")
        assertContains(workflow, "google-github-actions/auth@")
        assertContains(workflow, "workload_identity_provider:")
        assertContains(workflow, "docker push")
        assertContains(workflow, "${'$'}{{ github.sha }}")
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
    fun `migration 안전 규칙은 적용 파일 불변과 expand contract를 명시한다`() {
        val rules = read("docs/deployment/migration-safety.md")

        assertContains(rules, "적용된 migration 파일 수정 금지")
        assertContains(rules, "컬럼 추가")
        assertContains(rules, "코드 전환")
        assertContains(rules, "구 컬럼 제거")
        assertContains(rules, "이전 코드")
    }

    private fun read(relativePath: String): String {
        val path = root.resolve(relativePath)
        assertTrue(Files.isRegularFile(path), "필수 I2 파일이 없습니다: ${'$'}relativePath")
        return path.readText()
    }
}
