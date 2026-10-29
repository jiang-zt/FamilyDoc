import unittest
import init_db
class PasswordTest(unittest.TestCase):
    def test_generated_password_meets_medium_policy(self):
        for _ in range(10):
            password=init_db.new_password()
            self.assertGreaterEqual(len(password),32)
            self.assertTrue(any(c.isupper() for c in password))
            self.assertTrue(any(c.islower() for c in password))
            self.assertTrue(any(c.isdigit() for c in password))
            self.assertTrue(any(not c.isalnum() for c in password))
            self.assertNotIn("'",password)
if __name__=='__main__':unittest.main()
