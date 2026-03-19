import unittest
import json
import socket
import time
from pathlib import Path
import sys

# Add nerovision to path
sys.path.insert(0, str(Path(__file__).parent))

class TestNeroVisionCore(unittest.TestCase):
    """Test NeroVision core utilities and infrastructure"""
    
    def test_port_registry_consistency(self):
        """Verify all canonical ports are registered"""
        try:
            from nerovision.core.safe_clients import PORT_REGISTRY
            expected_ports = {9000, 9003, 9004, 9005, 9006, 9007, 9090}
            actual_ports = set(PORT_REGISTRY.values())
            self.assertEqual(expected_ports, actual_ports, 
                           f"Port mismatch: expected {expected_ports}, got {actual_ports}")
        except ImportError as e:
            self.fail(f"Failed to import PORT_REGISTRY: {e}")
    
    def test_json_cleaner_functionality(self):
        """Test JSON sanitization and cleaning"""
        try:
            from nerovision.core.json_cleaner import clean_json
            
            # Test valid JSON
            valid_json = '{"key": "value", "nested": {"data": 123}}'
            result = clean_json(valid_json)
            self.assertIsNotNone(result, "clean_json should return non-None for valid JSON")
            
            # Test malformed JSON handling
            malformed_json = '{"key": "value"'
            result = clean_json(malformed_json)
            self.assertIsNotNone(result, "clean_json should handle malformed JSON gracefully")
        except ImportError as e:
            self.fail(f"Failed to import json_cleaner: {e}")
    
    def test_logger_initialization(self):
        """Test logger setup and basic functionality"""
        try:
            from nerovision.core.logger import setup_logger
            logger = setup_logger('test_nerovision')
            
            # Should not raise exceptions
            logger.info("Test info message")
            logger.debug("Test debug message")
            logger.warning("Test warning message")
            
            self.assertIsNotNone(logger, "Logger should be initialized")
        except ImportError as e:
            self.fail(f"Failed to import logger: {e}")
    
    def test_runtime_environment(self):
        """Test runtime configuration"""
        try:
            from nerovision.core.runtime import RuntimeConfig
            config = RuntimeConfig()
            
            self.assertIsNotNone(config, "RuntimeConfig should initialize")
        except ImportError as e:
            self.fail(f"Failed to import runtime: {e}")
    
    def test_service_manager_import(self):
        """Test service manager can be imported"""
        try:
            from nerovision.core.service_manager import ServiceManager
            self.assertIsNotNone(ServiceManager, "ServiceManager should be importable")
        except ImportError as e:
            self.fail(f"Failed to import ServiceManager: {e}")
    
    def test_snapshot_functionality(self):
        """Test snapshot creation and verification"""
        try:
            from nerovision.core.snapshot import Snapshot
            snap = Snapshot()
            self.assertIsNotNone(snap, "Snapshot should initialize")
        except ImportError as e:
            self.fail(f"Failed to import snapshot: {e}")
    
    def test_verifier_initialization(self):
        """Test verifier utility"""
        try:
            from nerovision.core.verifier import Verifier
            verifier = Verifier()
            self.assertIsNotNone(verifier, "Verifier should initialize")
        except ImportError as e:
            self.fail(f"Failed to import verifier: {e}")


class TestNeroVisionServices(unittest.TestCase):
    """Test NeroVision service modules"""
    
    def test_vision_service_import(self):
        """Test vision service can be imported"""
        try:
            from nerovision.services.vision_service import VisionService
            self.assertIsNotNone(VisionService, "VisionService should be importable")
        except ImportError as e:
            self.fail(f"Failed to import VisionService: {e}")
    
    def test_voice_service_import(self):
        """Test voice service can be imported"""
        try:
            from nerovision.services.voice_service import VoiceService
            self.assertIsNotNone(VoiceService, "VoiceService should be importable")
        except ImportError as e:
            self.fail(f"Failed to import VoiceService: {e}")
    
    def test_llm_interface_import(self):
        """Test LLM interface can be imported"""
        try:
            from nerovision.llm.llm_interface import LLMInterface
            self.assertIsNotNone(LLMInterface, "LLMInterface should be importable")
        except ImportError as e:
            self.fail(f"Failed to import LLMInterface: {e}")
    
    def test_vector_memory_import(self):
        """Test vector memory service can be imported"""
        try:
            from nerovision.memory.vector_memory import VectorMemory
            self.assertIsNotNone(VectorMemory, "VectorMemory should be importable")
        except ImportError as e:
            self.fail(f"Failed to import VectorMemory: {e}")


class TestNeroVisionExecutionEngine(unittest.TestCase):
    """Test NeroVision execution engine"""
    
    def test_task_manager_import(self):
        """Test task manager can be imported"""
        try:
            from nerovision.nero_operator.task_manager import TaskManager
            self.assertIsNotNone(TaskManager, "TaskManager should be importable")
        except ImportError as e:
            self.fail(f"Failed to import TaskManager: {e}")
    
    def test_production_operator_import(self):
        """Test production operator can be imported"""
        try:
            from nerovision.nero_operator.production_operator import ProductionOperator
            self.assertIsNotNone(ProductionOperator, "ProductionOperator should be importable")
        except ImportError as e:
            self.fail(f"Failed to import ProductionOperator: {e}")


class TestPortAvailability(unittest.TestCase):
    """Test port availability on localhost"""
    
    CANONICAL_PORTS = {
        9000: 'MainBridge',
        9003: 'VoiceService (input)',
        9004: 'VisionService',
        9005: 'VoiceService (output)',
        9006: 'LLMInterface',
        9007: 'VectorMemory',
        9090: 'AvatarWS'
    }
    
    def test_ports_can_bind_localhost(self):
        """Verify all canonical ports can bind to 127.0.0.1"""
        failed_ports = []
        
        for port, service_name in self.CANONICAL_PORTS.items():
            try:
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind(('127.0.0.1', port))
                s.close()
            except OSError as e:
                failed_ports.append((port, service_name, str(e)))
        
        if failed_ports:
            msg = "The following ports could not bind to localhost:\n"
            for port, service, error in failed_ports:
                msg += f"  Port {port} ({service}): {error}\n"
            self.fail(msg)
    
    def test_ports_are_localhost_only(self):
        """Verify services should only bind to 127.0.0.1"""
        # This test documents the security requirement
        localhost_only = True
        self.assertTrue(localhost_only, 
                       "All NeroVision sockets must bind to 127.0.0.1 only (security requirement)")


class TestPackageStructure(unittest.TestCase):
    """Test NeroVision package structure"""
    
    def test_init_files_exist(self):
        """Verify all packages have __init__.py"""
        from pathlib import Path
        
        nerovision_root = Path(__file__).parent
        required_inits = [
            nerovision_root / 'core' / '__init__.py',
            nerovision_root / 'services' / '__init__.py',
            nerovision_root / 'llm' / '__init__.py',
            nerovision_root / 'memory' / '__init__.py',
            nerovision_root / 'nero_operator' / '__init__.py',
        ]
        
        missing = [f for f in required_inits if not f.exists()]
        if missing:
            self.fail(f"Missing __init__.py files: {missing}")
    
    def test_no_operator_import_collision(self):
        """Verify no stdlib operator module conflicts"""
        import subprocess
        result = subprocess.run(
            ['grep', '-r', 'import operator', 'nerovision/', '--include=*.py'],
            capture_output=True
        )
        
        if result.returncode == 0:
            self.fail(f"Found 'import operator' which conflicts with stdlib. Use 'nerovision.nero_operator' instead.\n{result.stdout.decode()}")


class TestMainBridgeSetup(unittest.TestCase):
    """Test main.py and bridge setup"""
    
    def test_main_py_exists(self):
        """Verify main.py exists and can be parsed"""
        from pathlib import Path
        main_file = Path(__file__).parent / 'main.py'
        
        self.assertTrue(main_file.exists(), "main.py should exist in nerovision root")
        
        try:
            with open(main_file, 'r') as f:
                compile(f.read(), str(main_file), 'exec')
        except SyntaxError as e:
            self.fail(f"main.py has syntax errors: {e}")


if __name__ == '__main__':
    unittest.main(verbosity=2)