# 🛡️ Comprehensive Code Validation System

This project uses a multi-layered validation system to prevent compilation errors and ensure code quality.

## 🚀 Quick Start

**Run validation manually:**
```bash
# Complete validation suite
./validate-code.sh && python3 validate-properties.py

# Just property validation
python3 validate-properties.py

# Just general validation
./validate-code.sh
```

**Automatic validation:**
- Pre-commit hook automatically runs validation
- Prevents commits with errors
- Ensures clean code in repository

## 🔧 Validation Components

### 1. General Code Validation (`validate-code.sh`)

**Phase 1: Class Definition Validation**
- Extracts class names and properties
- Documents available var/val declarations
- Provides reference for assignments

**Phase 2: Property Assignment Validation**
- Finds `.apply` blocks in code
- Checks property assignments
- Reports assignment patterns

**Phase 3: Import Validation**
- Validates internal package imports
- Checks if imported classes exist
- Reports missing dependencies

**Phase 4: Interface Implementation Validation**
- Checks interface implementations
- Validates class declarations
- Reports interface dependencies

**Phase 5: Method Call Validation**
- Checks ViewModel method calls
- Validates navigation calls
- Reports potential issues

**Phase 6: Compilation Error Patterns**
- Checks for val reassignment
- Finds unresolved references
- Validates function signatures

### 2. Property Assignment Validator (`validate-properties.py`)

**Smart Property Checking:**
- Parses class definitions from data classes
- Cross-references property assignments with actual class properties
- Prevents val reassignment errors
- Catches non-existent property assignments

**Features:**
- **Class Property Extraction**: Automatically parses `var`/`val` declarations
- **Assignment Validation**: Checks `.apply` blocks for valid assignments
- **Val Protection**: Prevents reassignment of immutable properties
- **Existence Checking**: Ensures assigned properties actually exist
- **Line Number Reporting**: Provides exact error locations

## 🎯 Error Prevention

### Caught Error Types:

1. **Val Reassignment**: `val property cannot be reassigned`
2. **Non-existent Properties**: Property doesn't exist in target class
3. **Missing Imports**: Imported class not found
4. **Interface Issues**: Missing interface implementations
5. **Method Call Errors**: Calls to non-existent methods

### Example Output:

```
❌ app/src/main/java/.../SongViewModel.kt:201 - Property 'wavDir' does not exist in class Song
❌ app/src/main/java/.../SongViewModel.kt:87 - Cannot reassign val property 'id' in class Song
✅ name = ... (valid var assignment)
```

## 🔄 Workflow Integration

### Pre-commit Hook

The pre-commit hook runs automatically:

```bash
🛡️ Running pre-commit validation...
📋 Step 1: Running bash validation...
📋 Step 2: Running property validation...
✅ All validations passed! Commit approved.
```

### Manual Validation

For development and debugging:

```bash
# Run before major changes
python3 validate-properties.py

# Run comprehensive check
./validate-code.sh
```

## 📊 Benefits

1. **Prevents Simple Errors**: Catches val reassignment, property typos
2. **Early Detection**: Finds issues before compilation
3. **Clean Commits**: Ensures only valid code enters repository
4. **Development Speed**: Faster feedback than full Gradle builds
5. **Code Quality**: Maintains consistent, error-free codebase

## 🎓 Usage Examples

### Before Making Changes:
```bash
# Check current state
python3 validate-properties.py
```

### During Development:
```bash
# After adding new properties
./validate-code.sh

# After refactoring assignments
python3 validate-properties.py
```

### Before Committing:
```bash
# Full validation (runs automatically)
git commit -m "your changes"
```

## 🔧 Customization

### Adding New Validations:

**In `validate-properties.py`:**
- Add new error patterns in `validate_property_assignments()`
- Extend class parsing in `parse_class_properties()`

**In `validate-code.sh`:**
- Add new validation phases
- Extend pattern matching
- Add file type checking

### Configuration:

**Validation Scope:**
- Modify file patterns in validation scripts
- Add/remove directories to check
- Customize error reporting levels

## 🚫 Bypassing Validation

**Emergency commits** (use sparingly):
```bash
git commit --no-verify -m "emergency fix"
```

**Temporary disable:**
```bash
chmod -x .git/hooks/pre-commit
```

**Re-enable:**
```bash
chmod +x .git/hooks/pre-commit
```

## 🎯 Success Metrics

With this validation system:
- ✅ **Zero val reassignment errors**
- ✅ **Zero property typos**
- ✅ **Clean compilation**
- ✅ **Faster development cycles**
- ✅ **Higher code quality**

**The validation system ensures that simple syntax errors are caught immediately, preventing the frustration of discovering them during compilation or runtime!** 🎵🚀