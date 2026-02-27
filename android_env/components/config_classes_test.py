# coding=utf-8
# Copyright 2026 DeepMind Technologies Limited.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Tests for android_env.components.config_classes."""

from absl.testing import absltest
from android_env.components import config_classes


class ConfigClassesTest(absltest.TestCase):

  def test_adb_controller_config_defaults(self):
    config = config_classes.AdbControllerConfig()
    self.assertEqual(config.adb_path, '~/Android/Sdk/platform-tools/adb')
    self.assertEqual(config.adb_server_port, 5037)
    self.assertEqual(config.default_timeout, 120.0)
    self.assertEqual(config.device_name, '')
    self.assertFalse(config.use_adb_server_port_from_os_env)

  def test_device_settings_config_defaults(self):
    config = config_classes.DeviceSettingsConfig()
    self.assertTrue(config.show_touches)
    self.assertTrue(config.show_pointer_location)
    self.assertFalse(config.show_status_bar)
    self.assertFalse(config.show_navigation_bar)

  def test_coordinator_config_defaults(self):
    config = config_classes.CoordinatorConfig()
    self.assertEqual(config.num_fingers, 1)
    self.assertFalse(config.enable_key_events)
    self.assertEqual(config.periodic_restart_time_min, 0.0)
    self.assertIsInstance(config.device_settings,
                          config_classes.DeviceSettingsConfig)

  def test_simulator_config_defaults(self):
    config = config_classes.SimulatorConfig()
    self.assertFalse(config.verbose_logs)
    self.assertEqual(config.interaction_rate_sec, 0.0)

  def test_gpu_mode_enum(self):
    self.assertEqual(config_classes.GPUMode.HOST.value, 'host')
    self.assertEqual(config_classes.GPUMode.SWANGLE_INDIRECT.value,
                     'swangle_indirect')
    self.assertEqual(config_classes.GPUMode.SWIFTSHADER_INDIRECT.value,
                     'swiftshader_indirect')

  def test_emulator_launcher_config_defaults(self):
    config = config_classes.EmulatorLauncherConfig()
    self.assertEqual(config.emulator_path, '~/Android/Sdk/emulator/emulator')
    self.assertEqual(config.android_sdk_root, '~/Android/Sdk')
    self.assertEqual(config.avd_name, '')
    self.assertEqual(config.android_avd_home, '~/.android/avd')
    self.assertEqual(config.snapshot_name, '')
    self.assertEqual(config.kvm_device, '/dev/kvm')
    self.assertEqual(config.tmp_dir, '/tmp/android_env/simulator/')
    self.assertEqual(config.gpu_mode,
                     config_classes.GPUMode.SWANGLE_INDIRECT.value)
    self.assertTrue(config.run_headless)
    self.assertFalse(config.restrict_network)
    self.assertFalse(config.show_perf_stats)
    self.assertEqual(config.adb_port, 0)
    self.assertEqual(config.emulator_console_port, 0)
    self.assertEqual(config.grpc_port, 0)

  def test_emulator_config_defaults(self):
    config = config_classes.EmulatorConfig()
    self.assertIsInstance(config, config_classes.SimulatorConfig)
    self.assertIsInstance(config.emulator_launcher,
                          config_classes.EmulatorLauncherConfig)
    self.assertIsInstance(config.adb_controller,
                          config_classes.AdbControllerConfig)
    self.assertEqual(config.logfile_path, '')
    self.assertEqual(config.launch_n_times_without_reboot, 1)
    self.assertEqual(config.launch_n_times_without_reinstall, 2)

  def test_fake_simulator_config_defaults(self):
    config = config_classes.FakeSimulatorConfig()
    self.assertIsInstance(config, config_classes.SimulatorConfig)
    self.assertEqual(config.screen_dimensions, (0, 0))

  def test_task_manager_config_defaults(self):
    config = config_classes.TaskManagerConfig()
    self.assertEqual(config.max_bad_states, 3)
    self.assertEqual(config.dumpsys_check_frequency, 150)
    self.assertEqual(config.max_failed_current_activity, 10)
    self.assertEqual(config.extras_max_buffer_size, 100)

  def test_task_config_defaults(self):
    config = config_classes.TaskConfig()
    self.assertEqual(config.tmp_dir, '')

  def test_filesystem_task_config_defaults(self):
    config = config_classes.FilesystemTaskConfig()
    self.assertIsInstance(config, config_classes.TaskConfig)
    self.assertEqual(config.path, '')

  def test_android_env_config_defaults(self):
    config = config_classes.AndroidEnvConfig()
    self.assertIsInstance(config.task, config_classes.TaskConfig)
    self.assertIsInstance(config.task_manager, config_classes.TaskManagerConfig)
    self.assertIsInstance(config.coordinator, config_classes.CoordinatorConfig)
    self.assertIsInstance(config.simulator, config_classes.SimulatorConfig)
    # Default simulator is EmulatorConfig
    self.assertIsInstance(config.simulator, config_classes.EmulatorConfig)


if __name__ == '__main__':
  absltest.main()
