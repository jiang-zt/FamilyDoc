import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('init_db', Path(__file__).with_name('init_db.py'))
init_db = importlib.util.module_from_spec(spec)
spec.loader.exec_module(init_db)


class InitDatabaseTest(unittest.TestCase):
    def run_main(self, output, environment, database):
        arguments = ['init_db.py', '--non-interactive', '--env-file', str(output), '--account-host', '%']
        with patch.dict(os.environ, environment, clear=True), patch.object(sys, 'argv', arguments), \
                patch.object(init_db, 'mysql', database), \
                patch.object(init_db.getpass, 'getpass', side_effect=AssertionError('must not prompt')), \
                patch.object(init_db, 'dev_value', side_effect=AssertionError('must not read dev config')):
            init_db.main()

    def test_missing_password_fails_without_connecting(self):
        with tempfile.TemporaryDirectory() as directory:
            from unittest.mock import Mock
            database = Mock()
            with self.assertRaisesRegex(RuntimeError, 'REPLAY_MYSQL_ADMIN_PASSWORD'):
                self.run_main(Path(directory) / 'ci.env', {}, database)
            database.assert_not_called()

    def test_initializes_schemas_restricted_account_and_can_repeat(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'ci.env'
            environment = {'REPLAY_MYSQL_ADMIN_PASSWORD': 'test-admin-password'}
            database = Mock(side_effect=['0\n0', '', 'ai_family_doctor_replay'])
            self.run_main(output, environment, database)
            sql = database.call_args_list[1].args[4]
            for table in ('chat_conversation', 'chat_generation', 'chat_shared_message', 'user_nutrition_profile'):
                self.assertIn(table, sql)
            self.assertIn("'family_replay'@'%'", sql)
            self.assertIn('GRANT SELECT,INSERT,UPDATE,DELETE ON `ai_family_doctor_replay`.*', sql)
            self.assertNotIn('GRANT ALL', sql)
            self.assertNotIn('test-admin-password', output.read_text())
            self.assertEqual(output.stat().st_mode & 0o777, 0o600)
            original = output.read_text()
            database = Mock(side_effect=['1\n1', '', 'ai_family_doctor_replay'])
            self.run_main(output, environment, database)
            self.assertEqual(output.read_text(), original)
            self.assertNotIn('ALTER USER', database.call_args_list[1].args[4])

    def test_does_not_adopt_existing_database_without_credentials(self):
        from unittest.mock import Mock
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / 'ci.env'
            with self.assertRaisesRegex(RuntimeError, '不自动接管'):
                self.run_main(output, {'REPLAY_MYSQL_ADMIN_PASSWORD': 'test'}, Mock(return_value='1\n1'))
            self.assertFalse(output.exists())

    def test_non_interactive_requires_explicit_output(self):
        with patch.object(sys, 'argv', ['init_db.py', '--non-interactive']), \
                patch.object(init_db, 'mysql') as database:
            with self.assertRaises(SystemExit) as failure:
                init_db.main()
            self.assertEqual(failure.exception.code, 2)
            database.assert_not_called()

    def test_non_interactive_rejects_dev_credentials(self):
        with patch.object(sys, 'argv', ['init_db.py', '--non-interactive', '--use-dev-credentials']), \
                patch.object(init_db, 'dev_value') as dev:
            with self.assertRaises(SystemExit) as failure:
                init_db.main()
            self.assertEqual(failure.exception.code, 2)
            dev.assert_not_called()


class PasswordTest(unittest.TestCase):
    def test_generated_password_meets_medium_policy(self):
        for _ in range(10):
            password = init_db.new_password()
            self.assertGreaterEqual(len(password), 32)
            self.assertTrue(any(c.isupper() for c in password))
            self.assertTrue(any(c.islower() for c in password))
            self.assertTrue(any(c.isdigit() for c in password))
            self.assertTrue(any(not c.isalnum() for c in password))
            self.assertNotIn("'", password)


if __name__ == '__main__':
    unittest.main()
