#!/usr/bin/env python3
"""
Property Assignment Validator
Prevents val reassignment and non-existent property errors
"""

import os
import re
from pathlib import Path
from typing import Dict, List, Set

class PropertyValidator:
    def __init__(self):
        self.class_properties: Dict[str, Dict[str, str]] = {}  # class -> {prop: type}
        self.errors: List[str] = []
        
    def parse_class_properties(self, file_path: str):
        """Parse a Kotlin file to extract class properties"""
        try:
            with open(file_path, 'r') as f:
                content = f.read()
                
            # Find class declarations
            class_matches = re.finditer(r'class\s+(\w+)', content)
            
            for class_match in class_matches:
                class_name = class_match.group(1)
                print(f"🔍 Analyzing class: {class_name}")
                
                # Extract properties (var/val declarations)
                properties = {}
                
                # Find var/val declarations
                property_pattern = r'(var|val)\s+(\w+)\s*:\s*(\w+[\?]?)'
                property_matches = re.finditer(property_pattern, content)
                
                for prop_match in property_matches:
                    prop_type = prop_match.group(1)  # var or val
                    prop_name = prop_match.group(2)
                    prop_kotlin_type = prop_match.group(3)
                    
                    properties[prop_name] = prop_type
                    print(f"   {prop_type} {prop_name}: {prop_kotlin_type}")
                
                self.class_properties[class_name] = properties
                print(f"   Total properties: {len(properties)}\n")
                
        except Exception as e:
            print(f"❌ Error parsing {file_path}: {e}")
    
    def validate_property_assignments(self, file_path: str):
        """Validate property assignments in .apply blocks"""
        try:
            with open(file_path, 'r') as f:
                content = f.read()
                
            print(f"🔍 Validating assignments in: {os.path.basename(file_path)}")
            
            # Find .apply blocks
            apply_pattern = r'(\w+)\(\)\.apply\s*\{([^}]+)\}'
            apply_matches = re.finditer(apply_pattern, content, re.DOTALL)
            
            for apply_match in apply_matches:
                class_name = apply_match.group(1)
                apply_body = apply_match.group(2)
                
                print(f"   Found .apply block for: {class_name}")
                
                if class_name not in self.class_properties:
                    print(f"   ⚠️  Class {class_name} not found in parsed classes")
                    continue
                
                class_props = self.class_properties[class_name]
                
                # Find property assignments within apply block
                assignment_pattern = r'(\w+)\s*=\s*'
                assignments = re.finditer(assignment_pattern, apply_body)
                
                for assignment in assignments:
                    prop_name = assignment.group(1)
                    line_num = content[:apply_match.start() + assignment.start()].count('\n') + 1
                    
                    if prop_name not in class_props:
                        error = f"❌ {file_path}:{line_num} - Property '{prop_name}' does not exist in class {class_name}"
                        print(f"   {error}")
                        self.errors.append(error)
                    elif class_props[prop_name] == 'val':
                        error = f"❌ {file_path}:{line_num} - Cannot reassign val property '{prop_name}' in class {class_name}"
                        print(f"   {error}")
                        self.errors.append(error)
                    else:
                        print(f"   ✅ {prop_name} = ... (valid var assignment)")
                        
        except Exception as e:
            print(f"❌ Error validating {file_path}: {e}")
    
    def run_validation(self):
        """Run the complete validation process"""
        print("🛡️ Property Assignment Validator")
        print("=" * 40)
        
        # Phase 1: Parse all data classes
        print("\n📋 Phase 1: Parsing Data Classes")
        print("-" * 30)
        
        data_dir = Path("app/src/main/java/com/georgv/audioworkstation/data")
        if data_dir.exists():
            for kt_file in data_dir.glob("*.kt"):
                self.parse_class_properties(str(kt_file))
        
        # Phase 2: Validate assignments in ViewModels and other files
        print("\n📋 Phase 2: Validating Assignments")
        print("-" * 35)
        
        # Check ViewModels
        vm_dir = Path("app/src/main/java/com/georgv/audioworkstation/ui/main")
        if vm_dir.exists():
            for kt_file in vm_dir.glob("*ViewModel.kt"):
                self.validate_property_assignments(str(kt_file))
        
        # Check Fragments
        fragment_dir = Path("app/src/main/java/com/georgv/audioworkstation/ui/main/fragments")
        if fragment_dir.exists():
            for kt_file in fragment_dir.glob("*.kt"):
                self.validate_property_assignments(str(kt_file))
        
        # Phase 3: Summary
        print("\n📊 Validation Results")
        print("-" * 20)
        
        if self.errors:
            print(f"❌ Found {len(self.errors)} errors:")
            for error in self.errors:
                print(f"  {error}")
            return False
        else:
            print("✅ All property assignments are valid!")
            return True

if __name__ == "__main__":
    validator = PropertyValidator()
    success = validator.run_validation()
    
    if not success:
        print("\n⚠️  Fix these errors before committing!")
        exit(1)
    else:
        print("\n🎯 Validation passed! Code is ready.")
        exit(0)