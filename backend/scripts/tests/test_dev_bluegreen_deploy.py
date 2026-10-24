import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


BACKEND_ROOT = Path(__file__).resolve().parents[2]
DEPLOY_SCRIPT = BACKEND_ROOT / "scripts" / "dev_bluegreen_deploy.sh"
PRESERVE_SCRIPT = BACKEND_ROOT / "scripts" / "dev_bluegreen_preserve_legacy.sh"
CORE_DEPLOY_SCRIPT = BACKEND_ROOT / "scripts" / "bluegreen_deploy.sh"
CORE_PRESERVE_SCRIPT = BACKEND_ROOT / "scripts" / "bluegreen_preserve_legacy.sh"
PROD_DEPLOY_SCRIPT = BACKEND_ROOT / "scripts" / "prod_bluegreen_deploy.sh"
PROD_PRESERVE_SCRIPT = BACKEND_ROOT / "scripts" / "prod_bluegreen_preserve_legacy.sh"
SYSTEMD_SOURCES = BACKEND_ROOT / "ops" / "systemd"
PROD_SYSTEMD_SOURCES = BACKEND_ROOT / "ops" / "systemd" / "prod"
PACKAGE_SCRIPT = BACKEND_ROOT / "ops" / "codebuild" / "package-deployment-artifact.sh"


class DevBlueGreenDeployTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.bundle = self.root / "bundle"
        self.scripts = self.bundle / "scripts"
        self.fake_bin = self.root / "bin"
        self.app_root = self.root / "app"
        self.systemd_dir = self.root / "systemd"
        self.state_root = self.root / "state"
        self.nginx_config = self.root / "nginx.conf"
        self.nginx_metrics_config = self.root / "nginx-metrics.conf"
        self.alloy_config = self.root / "config.alloy"
        self.active_services = self.root / "active-services"
        self.enabled_services = self.root / "enabled-services"
        self.command_log = self.root / "commands.log"

        self.scripts.mkdir(parents=True)
        (self.scripts / "systemd").mkdir()
        (self.scripts / "nginx").mkdir()
        (self.scripts / "certs").mkdir()
        self.fake_bin.mkdir()
        self.app_root.mkdir()
        self.systemd_dir.mkdir()
        self.bundle.joinpath("app.jar").write_bytes(b"candidate")
        self.scripts.joinpath("dev_bluegreen_deploy.sh").write_bytes(DEPLOY_SCRIPT.read_bytes())
        self.scripts.joinpath("dev_bluegreen_preserve_legacy.sh").write_bytes(PRESERVE_SCRIPT.read_bytes())
        self.scripts.joinpath("prod_bluegreen_deploy.sh").write_bytes(PROD_DEPLOY_SCRIPT.read_bytes())
        self.scripts.joinpath("prod_bluegreen_preserve_legacy.sh").write_bytes(PROD_PRESERVE_SCRIPT.read_bytes())
        self.scripts.joinpath("bluegreen_deploy.sh").write_bytes(CORE_DEPLOY_SCRIPT.read_bytes())
        self.scripts.joinpath("bluegreen_preserve_legacy.sh").write_bytes(CORE_PRESERVE_SCRIPT.read_bytes())
        shutil.copy2(
            BACKEND_ROOT / "ops" / "nginx" / "knot-backend-metrics.conf",
            self.scripts / "nginx" / "knot-backend-metrics.conf",
        )
        for unit_name in ("knot-backend-blue.service", "knot-backend-green.service"):
            shutil.copy2(SYSTEMD_SOURCES / unit_name, self.scripts / "systemd" / unit_name)

        self.legacy_jar = self.app_root / "app.jar"
        self.legacy_jar.write_bytes(b"active")
        self.nginx_config.write_text("server {\n  proxy_pass http://127.0.0.1:8080;\n}\n")
        self.alloy_config.write_text(
            'discovery.relabel "spring" {\n'
            '  targets = [{ __address__ = "127.0.0.1:8080", service = "knot-backend", env = "dev" }]\n'
            '}\n'
        )
        self.active_services.write_text("knot-backend.service\n")
        self.enabled_services.write_text("knot-backend.service\n")
        self._install_fake_commands()

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_switches_to_green_and_stops_legacy_blue_after_drain(self):
        alloy_config_inode = self.alloy_config.stat().st_ino

        result = self._run_deploy()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("succeeded", result.stdout)
        self.assertIn("proxy_pass http://127.0.0.1:8081;", self.nginx_config.read_text())
        self.assertIn("proxy_pass http://127.0.0.1:8081;", self.nginx_metrics_config.read_text())
        self.assertIn('__address__ = "127.0.0.1:18080"', self.alloy_config.read_text())
        self.assertEqual(self.alloy_config.stat().st_ino, alloy_config_inode)
        self.assertNotIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertIn("knot-backend-green.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend.service", self._read_lines(self.enabled_services))
        self.assertIn("knot-backend-green.service", self._read_lines(self.enabled_services))
        self.assertTrue((self.app_root / "slots" / "green" / "app.jar").is_file())
        curl_calls = [
            line for line in self.command_log.read_text().splitlines() if line.startswith("curl ")
        ]
        self.assertTrue(any("--resolve dev-api.knoted.kr:443:127.0.0.1" in line for line in curl_calls))
        self.assertFalse(any("--cacert" in line for line in curl_calls))

    def test_before_install_preserves_legacy_jar_before_code_deploy_cleanup(self):
        result = self._run_preserve()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.app_root / "slots" / "blue" / "app.jar").read_bytes(), b"active")
        self.assertEqual(
            (self.app_root / "slots" / "blue" / "app.jar").stat().st_mode & 0o777,
            0o500,
        )

    def test_before_install_refreshes_an_old_blue_backup_from_legacy_jar(self):
        blue_jar = self.app_root / "slots" / "blue" / "app.jar"
        blue_jar.parent.mkdir(parents=True)
        blue_jar.write_bytes(b"stale-backup")

        result = self._run_preserve()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(blue_jar.read_bytes(), b"active")

    def test_first_transition_bootstraps_preserved_blue_after_legacy_stop(self):
        preserve_result = self._run_preserve()
        self.assertEqual(preserve_result.returncode, 0, preserve_result.stdout + preserve_result.stderr)
        blue_jar = self.app_root / "slots" / "blue" / "app.jar"
        self.legacy_jar.unlink()
        self.active_services.write_text("")
        self.enabled_services.write_text("")
        self.command_log.write_text("")

        result = self._run_deploy(TEST_ACTIVE_JAR=str(blue_jar))

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("proxy_pass http://127.0.0.1:8081;", self.nginx_config.read_text())
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend-green.service"})
        commands = self.command_log.read_text().splitlines()
        self.assertLess(
            commands.index("systemctl start knot-backend-blue.service"),
            commands.index("systemctl start knot-backend-green.service"),
        )

    def test_first_transition_restores_legacy_service_if_blue_bootstrap_fails(self):
        preserve_result = self._run_preserve()
        self.assertEqual(preserve_result.returncode, 0, preserve_result.stdout + preserve_result.stderr)
        self.legacy_jar.unlink()
        self.active_services.write_text("")
        self.enabled_services.write_text("")
        blue_jar = self.app_root / "slots" / "blue" / "app.jar"

        result = self._run_deploy(
            DEPLOYMENT_GROUP_NAME="knot-backend-dev-deployment-group",
            TEST_ACTIVE_JAR=str(blue_jar),
            TEST_FAIL_START_SERVICE="knot-backend-blue.service",
            TEST_FAIL_STOP_INACTIVE_SERVICE="knot-backend-blue.service",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Could not start Blue; restored a service on port 8080", result.stdout)
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})
        self.assertEqual(self.legacy_jar.read_bytes(), b"active")

    def test_first_transition_restores_legacy_service_if_systemd_reload_fails(self):
        preserve_result = self._run_preserve()
        self.assertEqual(preserve_result.returncode, 0, preserve_result.stdout + preserve_result.stderr)
        self.legacy_jar.unlink()
        self.active_services.write_text("")
        self.enabled_services.write_text("")
        blue_jar = self.app_root / "slots" / "blue" / "app.jar"

        result = self._run_deploy(
            DEPLOYMENT_GROUP_NAME="knot-backend-dev-deployment-group",
            TEST_ACTIVE_JAR=str(blue_jar),
            TEST_FAIL_DAEMON_RELOAD="1",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Could not reload systemd; restored a service", result.stdout)
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})
        self.assertEqual(self.legacy_jar.read_bytes(), b"active")

    def test_candidate_health_failure_keeps_legacy_route_and_service(self):
        result = self._run_deploy(TEST_FAIL_PORT="8081")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend-green.service", self._read_lines(self.active_services))

    def test_redirected_health_response_is_not_ready(self):
        result = self._run_deploy(TEST_REDIRECT_PORT="8081")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend-green.service", self._read_lines(self.active_services))

    def test_nested_component_up_does_not_override_root_down(self):
        result = self._run_deploy(TEST_ROOT_DOWN_COMPONENT_UP="1")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend-green.service", self._read_lines(self.active_services))

    def test_nginx_validation_failure_restores_the_previous_config(self):
        result = self._run_deploy(TEST_FAIL_NGINX_TEST="1")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertIn("knot-backend-green.service", self._read_lines(self.active_services))
        self.assertIn("previous route was not confirmed restored", result.stdout)
        self.assertTrue((self.state_root / "route-uncertain").is_file())

    def test_uncertain_route_marker_blocks_a_following_deployment(self):
        self._run_deploy(TEST_FAIL_NGINX_RELOAD="1")
        previous_command_log = self.command_log.read_text()

        result = self._run_deploy()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("previous route rollback is uncertain", result.stdout)
        self.assertEqual(self.command_log.read_text(), previous_command_log)

    def test_nginx_reload_failure_preserves_both_processes(self):
        result = self._run_deploy(TEST_FAIL_NGINX_RELOAD="1")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertIn("knot-backend-green.service", self._read_lines(self.active_services))
        self.assertIn("previous route was not confirmed restored", result.stdout)
        self.assertTrue((self.state_root / "route-uncertain").is_file())

    def test_proxy_health_failure_restores_the_previous_route(self):
        result = self._run_deploy(TEST_FAIL_PROXY="1")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend-green.service", self._read_lines(self.active_services))

    def test_drain_timeout_rolls_route_back_without_stopping_blue(self):
        result = self._run_deploy(
            KNOT_BLUEGREEN_DRAIN_TIMEOUT_SECONDS="0",
            TEST_NGINX_CONNECTIONS_8080="1",
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Drain timed out", result.stdout)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend-green.service", self._read_lines(self.active_services))

    def test_idle_alloy_connection_does_not_block_drain(self):
        result = self._run_deploy(TEST_ALLOY_CONNECTIONS_8080="1")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("knot-backend-green.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend.service", self._read_lines(self.active_services))

    def test_metrics_validation_failure_restores_routes_before_stopping_candidate(self):
        result = self._run_deploy(TEST_FAIL_METRICS="1")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertFalse(self.nginx_metrics_config.exists())
        self.assertIn('__address__ = "127.0.0.1:8080"', self.alloy_config.read_text())
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})

    def test_failed_alloy_reload_is_retried_on_the_next_deployment(self):
        failed_result = self._run_deploy(TEST_FAIL_ALLOY_RELOAD="1")

        self.assertNotEqual(failed_result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_metrics_config.read_text())
        self.assertIn('__address__ = "127.0.0.1:18080"', self.alloy_config.read_text())
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})

        retry_result = self._run_deploy()

        self.assertEqual(retry_result.returncode, 0, retry_result.stdout + retry_result.stderr)
        reload_attempts = [
            line for line in self.command_log.read_text().splitlines() if line == "alloy-reload"
        ]
        self.assertEqual(len(reload_attempts), 2)

    def test_unchanged_alloy_config_is_not_reloaded_for_every_deployment(self):
        first_result = self._run_deploy()

        self.assertEqual(first_result.returncode, 0, first_result.stdout + first_result.stderr)
        second_result = self._run_deploy()

        self.assertEqual(second_result.returncode, 0, second_result.stdout + second_result.stderr)
        reload_attempts = [
            line for line in self.command_log.read_text().splitlines() if line == "alloy-reload"
        ]
        self.assertEqual(len(reload_attempts), 1)

    def test_old_service_stop_failure_rolls_route_back(self):
        result = self._run_deploy(TEST_FAIL_STOP_SERVICE="knot-backend.service")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend-green.service", self._read_lines(self.active_services))

    def test_migration_set_change_is_rejected_before_candidate_start(self):
        result = self._run_deploy(TEST_CANDIDATE_MIGRATIONS="V1__initial.sql\nV2__new_table.sql")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Flyway migration file set changed", result.stdout)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})

    def test_second_deployment_uses_blue_slot_when_green_is_active(self):
        green_jar = self.app_root / "slots" / "green" / "app.jar"
        green_jar.parent.mkdir(parents=True)
        green_jar.write_bytes(b"active-green")
        self.nginx_config.write_text("server {\n  proxy_pass http://127.0.0.1:8081;\n}\n")
        self.active_services.write_text("knot-backend-green.service\n")
        self.enabled_services.write_text("knot-backend-green.service\n")

        result = self._run_deploy(TEST_ACTIVE_JAR=str(green_jar))

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertIn("knot-backend-blue.service", self._read_lines(self.active_services))
        self.assertNotIn("knot-backend-green.service", self._read_lines(self.active_services))
        self.assertTrue((self.app_root / "slots" / "blue" / "app.jar").is_file())

    def test_unexpected_deployment_group_is_rejected_without_touching_blue(self):
        result = self._run_deploy(DEPLOYMENT_GROUP_NAME="knot-backend-dev-bluegreen")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing deployment group", result.stdout)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})

    def test_prod_profile_switches_traffic_and_uses_the_prod_group_and_health_host(self):
        for unit_name in ("knot-backend-blue.service", "knot-backend-green.service"):
            shutil.copy2(PROD_SYSTEMD_SOURCES / unit_name, self.scripts / "systemd" / unit_name)
        shutil.copy2(
            BACKEND_ROOT / "ops" / "certificates" / "cloudflare-origin-rsa-root.pem",
            self.scripts / "certs" / "cloudflare-origin-rsa-root.pem",
        )
        environment = self._deployment_environment()
        environment.update(
            {
                "KNOT_BLUEGREEN_PROFILE": "prod",
                "DEPLOYMENT_GROUP_NAME": "knot-backend-deployment-group",
                "KNOT_BLUEGREEN_HEALTH_HOST": "api.knoted.kr",
            }
        )

        result = subprocess.run(
            ["bash", str(self.scripts / "prod_bluegreen_deploy.sh")],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("[blue-green:prod]", result.stdout)
        self.assertIn("proxy_pass http://127.0.0.1:8081;", self.nginx_config.read_text())
        self.assertIn("knot-backend-green.service", self._read_lines(self.active_services))
        self.assertIn("-Xmx256m", (self.systemd_dir / "knot-backend-green.service").read_text())
        self.assertIn('__address__ = "127.0.0.1:18080"', self.alloy_config.read_text())
        curl_urls = [
            line for line in self.command_log.read_text().splitlines() if line.startswith("curl ")
        ]
        self.assertTrue(any("https://api.knoted.kr/actuator/health" in line for line in curl_urls))
        self.assertTrue(
            any(
                "--cacert "
                + str(self.scripts / "certs" / "cloudflare-origin-rsa-root.pem")
                in line
                and "https://api.knoted.kr/actuator/health" in line
                for line in curl_urls
            )
        )

    def test_prod_deploy_fails_before_starting_green_when_origin_ca_is_missing(self):
        for unit_name in ("knot-backend-blue.service", "knot-backend-green.service"):
            shutil.copy2(PROD_SYSTEMD_SOURCES / unit_name, self.scripts / "systemd" / unit_name)
        environment = self._deployment_environment()
        environment.update(
            {
                "KNOT_BLUEGREEN_PROFILE": "prod",
                "DEPLOYMENT_GROUP_NAME": "knot-backend-deployment-group",
                "KNOT_BLUEGREEN_HEALTH_HOST": "api.knoted.kr",
            }
        )

        result = subprocess.run(
            ["bash", str(self.scripts / "prod_bluegreen_deploy.sh")],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Prod Cloudflare Origin RSA CA root is missing", result.stdout)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})
        commands = self.command_log.read_text().splitlines() if self.command_log.exists() else []
        self.assertNotIn("systemctl start knot-backend-green.service", commands)

    def test_prod_deploy_keeps_blue_route_when_current_nginx_health_fails(self):
        for unit_name in ("knot-backend-blue.service", "knot-backend-green.service"):
            shutil.copy2(PROD_SYSTEMD_SOURCES / unit_name, self.scripts / "systemd" / unit_name)
        shutil.copy2(
            BACKEND_ROOT / "ops" / "certificates" / "cloudflare-origin-rsa-root.pem",
            self.scripts / "certs" / "cloudflare-origin-rsa-root.pem",
        )
        environment = self._deployment_environment()
        environment.update(
            {
                "KNOT_BLUEGREEN_PROFILE": "prod",
                "DEPLOYMENT_GROUP_NAME": "knot-backend-deployment-group",
                "KNOT_BLUEGREEN_HEALTH_HOST": "api.knoted.kr",
                "TEST_FAIL_PROXY": "1",
            }
        )

        result = subprocess.run(
            ["bash", str(self.scripts / "prod_bluegreen_deploy.sh")],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Current Prod Nginx health check failed before deployment", result.stdout)
        self.assertIn("proxy_pass http://127.0.0.1:8080;", self.nginx_config.read_text())
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})
        commands = self.command_log.read_text().splitlines()
        self.assertNotIn("systemctl start knot-backend-green.service", commands)

    def test_prod_slot_units_keep_runtime_environment_and_set_the_requested_heap_limit(self):
        for slot_name, port in (("blue", "8080"), ("green", "8081")):
            unit = (PROD_SYSTEMD_SOURCES / f"knot-backend-{slot_name}.service").read_text()
            self.assertIn("EnvironmentFile=/etc/knot-backend/knot-backend.env", unit)
            self.assertIn("EnvironmentFile=/etc/knot-backend/recording-storage.env", unit)
            self.assertIn("EnvironmentFile=/etc/knot-backend/recording-ai.env", unit)
            self.assertIn("Environment=SPRING_PROFILES_INCLUDE=observability", unit)
            self.assertIn(
                "Environment=SPRING_CONFIG_ADDITIONAL_LOCATION=file:/etc/knot-backend/recording-streaming.properties",
                unit,
            )
            self.assertIn(f"java -Xmx256m -XX:NativeMemoryTracking=summary", unit)
            self.assertIn(f"--server.port={port}", unit)

    def test_prod_deploy_rejects_a_non_prod_deployment_group(self):
        environment = self._deployment_environment()
        environment.update(
            {
                "KNOT_BLUEGREEN_PROFILE": "prod",
                "DEPLOYMENT_GROUP_NAME": "knot-backend-dev-deployment-group",
            }
        )

        result = subprocess.run(
            ["bash", str(self.scripts / "prod_bluegreen_deploy.sh")],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing deployment group", result.stdout)
        self.assertEqual(self._read_lines(self.active_services), {"knot-backend.service"})

    def test_prod_preserve_rejects_the_dev_deployment_group(self):
        environment = self._preserve_environment()
        environment.update({"KNOT_BLUEGREEN_PROFILE": "prod"})

        result = subprocess.run(
            ["bash", str(self.scripts / "prod_bluegreen_preserve_legacy.sh")],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Refusing deployment group", result.stdout)
        self.assertTrue(self.legacy_jar.is_file())

    def _run_deploy(self, **extra_environment):
        environment = self._deployment_environment()
        environment.update(extra_environment)
        return subprocess.run(
            ["bash", str(self.scripts / "dev_bluegreen_deploy.sh")],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

    def _run_preserve(self, **extra_environment):
        environment = self._preserve_environment()
        environment.update(extra_environment)
        return subprocess.run(
            ["bash", str(self.scripts / "dev_bluegreen_preserve_legacy.sh")],
            check=False,
            capture_output=True,
            text=True,
            env=environment,
        )

    def _deployment_environment(self):
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": f"{self.fake_bin}{os.pathsep}{environment['PATH']}",
                "DEPLOYMENT_GROUP_NAME": "knot-backend-dev-deployment-group",
                "DEPLOYMENT_ID": "d-test123",
                "KNOT_BLUEGREEN_APP_ROOT": str(self.app_root),
                "KNOT_BLUEGREEN_NGINX_CONFIG": str(self.nginx_config),
                "KNOT_BLUEGREEN_NGINX_METRICS_CONFIG": str(self.nginx_metrics_config),
                "KNOT_BLUEGREEN_ALLOY_CONFIG": str(self.alloy_config),
                "KNOT_BLUEGREEN_SYSTEMD_DIR": str(self.systemd_dir),
                "KNOT_BLUEGREEN_STATE_ROOT": str(self.state_root),
                "KNOT_BLUEGREEN_LOCK_FILE": str(self.root / "bluegreen.lock"),
                "KNOT_BLUEGREEN_HEALTH_ATTEMPTS": "1",
                "KNOT_BLUEGREEN_HEALTH_INTERVAL_SECONDS": "0",
                "KNOT_BLUEGREEN_CURL_TIMEOUT_SECONDS": "1",
                "KNOT_BLUEGREEN_DRAIN_TIMEOUT_SECONDS": "0",
                "KNOT_BLUEGREEN_DRAIN_POLL_INTERVAL_SECONDS": "1",
                "KNOT_BLUEGREEN_HEALTH_HOST": "dev-api.knoted.kr",
                "TEST_ACTIVE_JAR": str(self.legacy_jar),
                "TEST_ACTIVE_MIGRATIONS": "V1__initial.sql",
                "TEST_CANDIDATE_MIGRATIONS": "V1__initial.sql",
                "TEST_ACTIVE_SERVICES_FILE": str(self.active_services),
                "TEST_ENABLED_SERVICES_FILE": str(self.enabled_services),
                "TEST_COMMAND_LOG": str(self.command_log),
            }
        )
        return environment

    def _preserve_environment(self):
        environment = os.environ.copy()
        environment.update(
            {
                "PATH": f"{self.fake_bin}{os.pathsep}{environment['PATH']}",
                "DEPLOYMENT_GROUP_NAME": "knot-backend-dev-deployment-group",
                "DEPLOYMENT_ID": "d-test123",
                "KNOT_BLUEGREEN_APP_ROOT": str(self.app_root),
                "KNOT_BLUEGREEN_NGINX_CONFIG": str(self.nginx_config),
                "TEST_ACTIVE_SERVICES_FILE": str(self.active_services),
                "TEST_ENABLED_SERVICES_FILE": str(self.enabled_services),
                "TEST_COMMAND_LOG": str(self.command_log),
            }
        )
        return environment

    def _read_lines(self, path):
        return {line for line in path.read_text().splitlines() if line}

    def _install_fake_commands(self):
        self._write_command(
            "systemctl",
            """#!/usr/bin/env python3
import os
import sys
from pathlib import Path

command = sys.argv[1]
arguments = sys.argv[2:]
active_file = Path(os.environ["TEST_ACTIVE_SERVICES_FILE"])
enabled_file = Path(os.environ["TEST_ENABLED_SERVICES_FILE"])
log_file = Path(os.environ["TEST_COMMAND_LOG"])
with log_file.open("a") as output:
    output.write("systemctl " + " ".join(sys.argv[1:]) + "\\n")

def read_services(path):
    return {line for line in path.read_text().splitlines() if line}

def write_services(path, services):
    path.write_text("".join(service + "\\n" for service in sorted(services)))

if command in {"is-active", "is-enabled"}:
    service = arguments[-1]
    source = active_file if command == "is-active" else enabled_file
    present = service in read_services(source)
    if command == "is-enabled":
        print("enabled" if present else "disabled")
    sys.exit(0 if present else 3)
if command in {"start", "enable"}:
    service = arguments[-1]
    failure_setting = "TEST_FAIL_" + command.upper() + "_SERVICE"
    if os.environ.get(failure_setting) == service:
        sys.exit(1)
    source = active_file if command == "start" else enabled_file
    services = read_services(source)
    services.add(service)
    write_services(source, services)
    sys.exit(0)
if command in {"stop", "disable"}:
    service = arguments[-1]
    if command == "stop" and os.environ.get("TEST_FAIL_STOP_SERVICE") == service:
        sys.exit(1)
    source = active_file if command == "stop" else enabled_file
    services = read_services(source)
    if command == "stop" and os.environ.get("TEST_FAIL_STOP_INACTIVE_SERVICE") == service and service not in services:
        sys.exit(1)
    services.discard(service)
    write_services(source, services)
    sys.exit(0)
if command == "daemon-reload" and os.environ.get("TEST_FAIL_DAEMON_RELOAD") == "1":
    sys.exit(1)
if command in {"daemon-reload", "reload"}:
    sys.exit(0)
sys.exit(0)
""",
        )
        self._write_command(
            "curl",
            """#!/usr/bin/env python3
import os
import sys

url = sys.argv[-1]
with open(os.environ["TEST_COMMAND_LOG"], "a") as output:
    output.write("curl " + " ".join(sys.argv[1:]).replace("\\n", "\\\\n") + "\\n")
if url.endswith("/-/ready"):
    print("ready")
    sys.exit(0)
if url.endswith("/-/reload"):
    with open(os.environ["TEST_COMMAND_LOG"], "a") as output:
        output.write("alloy-reload\\n")
    sys.exit(1 if os.environ.get("TEST_FAIL_ALLOY_RELOAD") == "1" else 0)
if "127.0.0.1:18080/actuator/prometheus" in url:
    if os.environ.get("TEST_FAIL_METRICS") == "1":
        sys.exit(7)
    print("jvm_memory_used_bytes 123")
    sys.exit(0)
if "127.0.0.1:8080" in url and os.environ.get("TEST_FAIL_PORT") == "8080":
    print('{"status":"DOWN"}')
    print(os.environ.get("TEST_HTTP_STATUS", "200"))
    sys.exit(0)
if "127.0.0.1:8081" in url and os.environ.get("TEST_FAIL_PORT") == "8081":
    print('{"status":"DOWN"}')
    print(os.environ.get("TEST_HTTP_STATUS", "200"))
    sys.exit(0)
if "127.0.0.1:8081" in url and os.environ.get("TEST_REDIRECT_PORT") == "8081":
    print('{"status":"UP"}')
    print("302")
    sys.exit(0)
if "127.0.0.1:8081" in url and os.environ.get("TEST_ROOT_DOWN_COMPONENT_UP") == "1":
    print('{"status":"DOWN","components":{"database":{"status":"UP"}}}')
    print(os.environ.get("TEST_HTTP_STATUS", "200"))
    sys.exit(0)
if url.startswith("https://") and os.environ.get("TEST_FAIL_PROXY") == "1":
    print('{"status":"DOWN"}')
    print(os.environ.get("TEST_HTTP_STATUS", "200"))
    sys.exit(0)
print('{"status":"UP"}')
print(os.environ.get("TEST_HTTP_STATUS", "200"))
""",
        )
        self._write_command(
            "nginx",
            """#!/usr/bin/env python3
import os
import sys

if sys.argv[1:] == ["-t"] and os.environ.get("TEST_FAIL_NGINX_TEST") == "1":
    sys.exit(1)
if sys.argv[1:] == ["-s", "reload"] and os.environ.get("TEST_FAIL_NGINX_RELOAD") == "1":
    sys.exit(1)
sys.exit(0)
""",
        )
        self._write_command(
            "ss",
            """#!/usr/bin/env python3
import os
import sys

arguments = " ".join(sys.argv[1:])
port = "8080" if ":8080" in arguments else "8081"
if "dport" in arguments:
    nginx_count = int(os.environ.get("TEST_NGINX_CONNECTIONS_" + port, "0"))
    alloy_count = int(os.environ.get("TEST_ALLOY_CONNECTIONS_" + port, "0"))
    for index in range(nginx_count):
        print('ESTAB 0 0 127.0.0.1:50000 127.0.0.1:' + port + ' users:(("nginx",pid=42,fd=21))')
    for index in range(alloy_count):
        print('ESTAB 0 0 127.0.0.1:50001 127.0.0.1:' + port + ' users:(("alloy",pid=43,fd=22))')
    sys.exit(0)
count = int(os.environ.get("TEST_CONNECTIONS_" + port, "0"))
for index in range(count):
    print("ESTAB 0 0 127.0.0.1:" + port + " 127.0.0.1:50000")
""",
        )
        self._write_command(
            "jar",
            """#!/usr/bin/env python3
import os
import sys

jar_path = sys.argv[-1]
active_path = os.environ["TEST_ACTIVE_JAR"]
variable = "TEST_ACTIVE_MIGRATIONS" if jar_path == active_path else "TEST_CANDIDATE_MIGRATIONS"
for migration in os.environ.get(variable, "").splitlines():
    print("BOOT-INF/classes/db/migration/" + migration)
""",
        )
        self._write_command(
            "install",
            """#!/usr/bin/env python3
import os
import shutil
import sys
from pathlib import Path

arguments = sys.argv[1:]
directory = "-d" in arguments
mode = None
filtered = []
index = 0
while index < len(arguments):
    argument = arguments[index]
    if argument in {"-d"}:
        index += 1
        continue
    if argument == "-m":
        mode = arguments[index + 1]
        index += 2
        continue
    if argument in {"-o", "-g"}:
        index += 2
        continue
    filtered.append(argument)
    index += 1
target = Path(filtered[-1])
if directory:
    target.mkdir(parents=True, exist_ok=True)
    if mode:
        target.chmod(int(mode, 8))
    sys.exit(0)
source = Path(filtered[-2])
target.parent.mkdir(parents=True, exist_ok=True)
target.unlink(missing_ok=True)
shutil.copyfile(source, target)
if mode:
    target.chmod(int(mode, 8))
""",
        )
        self._write_command("chown", "#!/bin/sh\nexit 0\n")
        self._write_command("flock", "#!/bin/sh\nexit 0\n")

    def _write_command(self, name, source):
        command_path = self.fake_bin / name
        command_path.write_text(source)
        command_path.chmod(0o755)


class DeploymentArtifactPackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.root = Path(self.temp_dir.name)
        self.backend = self.root / "backend"
        self.backend.mkdir()
        (self.backend / "scripts").mkdir()
        (self.backend / "scripts" / "tests").mkdir()
        (self.backend / "ops" / "systemd").mkdir(parents=True)
        (self.backend / "ops" / "systemd" / "prod").mkdir(parents=True)
        (self.backend / "ops" / "codebuild").mkdir(parents=True)
        (self.backend / "ops" / "nginx").mkdir(parents=True)
        (self.backend / "ops" / "certificates").mkdir(parents=True)
        (self.backend / "scripts" / "sample-hook.sh").write_text("#!/bin/sh\nexit 0\n")
        (self.backend / "scripts" / "dev_bluegreen_preserve_legacy.sh").write_text(
            "#!/bin/sh\nprintf 'preserve\\n'\n"
        )
        (self.backend / "scripts" / "prod_bluegreen_deploy.sh").write_text(
            "#!/bin/sh\nprintf 'prod deploy\\n'\n"
        )
        (self.backend / "scripts" / "tests" / "fixture.py").write_text("print('test')\n")
        (self.backend / "appspec.yml").write_text("legacy-appspec\n")
        (self.backend / "appspec-dev-bluegreen.yml").write_text("bluegreen-appspec\n")
        (self.backend / "appspec-prod-bluegreen.yml").write_text("prod-bluegreen-appspec\n")
        (self.backend / "ops" / "systemd" / "knot-backend-blue.service").write_text("blue-unit\n")
        (self.backend / "ops" / "systemd" / "knot-backend-green.service").write_text("green-unit\n")
        (self.backend / "ops" / "systemd" / "prod" / "knot-backend-blue.service").write_text("prod-blue-unit\n")
        (self.backend / "ops" / "systemd" / "prod" / "knot-backend-green.service").write_text("prod-green-unit\n")
        (self.backend / "ops" / "nginx" / "knot-backend-metrics.conf").write_text("metrics-route\n")
        (self.backend / "ops" / "certificates" / "cloudflare-origin-rsa-root.pem").write_text(
            "cloudflare-origin-root\n"
        )

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_legacy_mode_packages_only_the_existing_appspec_and_hooks(self):
        result = self._package("legacy-in-place")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / "appspec.yml").read_text(), "legacy-appspec\n")
        self.assertTrue((self.root / "scripts" / "sample-hook.sh").is_file())
        self.assertFalse((self.root / "scripts" / "tests").exists())
        self.assertFalse((self.root / "scripts" / "systemd").exists())
        self.assertFalse((self.root / "scripts" / "nginx").exists())
        self.assertFalse((self.root / "scripts" / "certs").exists())

    def test_unset_mode_defaults_to_legacy_artifact(self):
        result = self._package(None)

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / "appspec.yml").read_text(), "legacy-appspec\n")
        self.assertFalse((self.root / "scripts" / "systemd").exists())

    def test_dev_bluegreen_mode_packages_the_isolated_appspec_and_units(self):
        result = self._package("dev-bluegreen")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / "appspec.yml").read_text(), "bluegreen-appspec\n")
        self.assertFalse((self.root / "scripts" / "tests").exists())
        self.assertEqual(
            (self.root / "scripts" / "systemd" / "knot-backend-blue.service").read_text(),
            "blue-unit\n",
        )
        self.assertEqual(
            (self.root / "scripts" / "systemd" / "knot-backend-green.service").read_text(),
            "green-unit\n",
        )
        self.assertEqual(
            (self.root / "scripts" / "nginx" / "knot-backend-metrics.conf").read_text(),
            "metrics-route\n",
        )
        self.assertFalse((self.root / "scripts" / "certs").exists())
        self.assertTrue((self.root / "scripts" / "dev_bluegreen_preserve_legacy.sh").is_file())

    def test_prod_bluegreen_mode_packages_prod_hooks_units_and_metrics(self):
        result = self._package("prod-bluegreen")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.root / "appspec.yml").read_text(), "prod-bluegreen-appspec\n")
        self.assertEqual(
            (self.root / "scripts" / "systemd" / "knot-backend-blue.service").read_text(),
            "prod-blue-unit\n",
        )
        self.assertEqual(
            (self.root / "scripts" / "systemd" / "knot-backend-green.service").read_text(),
            "prod-green-unit\n",
        )
        self.assertEqual(
            (self.root / "scripts" / "nginx" / "knot-backend-metrics.conf").read_text(),
            "metrics-route\n",
        )
        self.assertEqual(
            (
                self.root
                / "scripts"
                / "certs"
                / "cloudflare-origin-rsa-root.pem"
            ).read_text(),
            "cloudflare-origin-root\n",
        )
        self.assertTrue((self.root / "scripts" / "prod_bluegreen_deploy.sh").is_file())

    def test_legacy_mode_removes_stale_bluegreen_files(self):
        stale_systemd = self.root / "scripts" / "systemd"
        stale_nginx = self.root / "scripts" / "nginx"
        stale_certs = self.root / "scripts" / "certs"
        stale_systemd.mkdir(parents=True)
        stale_nginx.mkdir(parents=True)
        stale_certs.mkdir(parents=True)
        (stale_systemd / "old.service").write_text("stale\n")
        (stale_nginx / "old.conf").write_text("stale\n")
        (stale_certs / "old.pem").write_text("stale\n")

        result = self._package("legacy-in-place")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertFalse(stale_systemd.exists())
        self.assertFalse(stale_nginx.exists())
        self.assertFalse(stale_certs.exists())

    def test_unknown_mode_fails_before_creating_an_appspec(self):
        result = self._package("unsupported")

        self.assertEqual(result.returncode, 2)
        self.assertFalse((self.root / "appspec.yml").exists())

    def _package(self, deployment_mode):
        environment = os.environ.copy()
        environment.pop("DEPLOYMENT_MODE", None)
        if deployment_mode is not None:
            environment["DEPLOYMENT_MODE"] = deployment_mode
        return subprocess.run(
            ["bash", str(PACKAGE_SCRIPT)],
            check=False,
            capture_output=True,
            text=True,
            cwd=self.root,
            env=environment,
        )


if __name__ == "__main__":
    unittest.main()
