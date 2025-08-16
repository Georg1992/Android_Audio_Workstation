#!/bin/bash

# Comprehensive Kotlin Code Validation Script
# Run this before every commit to catch syntax errors early

echo "🔍 Starting Comprehensive Code Validation..."

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Track errors
ERRORS=0

echo -e "\n📋 Phase 1: Class Definition Validation"
echo "=========================================="

# Function to validate class properties
validate_class_properties() {
    local file=$1
    echo "🔍 Validating: $file"
    
    # Extract class name
    local class_name=$(grep -o "class [A-Za-z]*" "$file" | head -1 | cut -d' ' -f2)
    
    if [ -n "$class_name" ]; then
        echo "   Class: $class_name"
        
        # Extract all var/val declarations
        echo "   Properties:"
        grep -n "var\|val" "$file" | grep -v "private val _" | while read line; do
            echo "     $line"
        done
        echo ""
    fi
}

# Check all data classes first
echo "📂 Checking Data Classes..."
for file in app/src/main/java/com/georgv/audioworkstation/data/*.kt; do
    if [ -f "$file" ]; then
        validate_class_properties "$file"
    fi
done

echo -e "\n📋 Phase 2: Property Assignment Validation"
echo "============================================="

# Function to check for property assignments
check_property_assignments() {
    local file=$1
    echo "🔍 Checking property assignments in: $(basename $file)"
    
    # Look for .apply blocks and property assignments
    grep -n "\.apply\s*{" "$file" && {
        echo "   Found .apply block - checking property assignments:"
        # Extract lines between .apply { and }
        awk '/\.apply\s*\{/,/^\s*\}/' "$file" | grep -n "=" | while read assignment; do
            property=$(echo "$assignment" | cut -d'=' -f1 | xargs)
            echo "     Assignment: $property"
        done
    }
    echo ""
}

# Check ViewModel files for property assignments
echo "📂 Checking ViewModels..."
for file in app/src/main/java/com/georgv/audioworkstation/ui/main/*ViewModel.kt; do
    if [ -f "$file" ]; then
        check_property_assignments "$file"
    fi
done

echo -e "\n📋 Phase 3: Import Validation"
echo "=============================="

# Function to validate imports
validate_imports() {
    local file=$1
    echo "🔍 Checking imports in: $(basename $file)"
    
    # Extract all imports
    grep "^import" "$file" | while read import_line; do
        import_class=$(echo "$import_line" | sed 's/import //' | sed 's/.*\.//')
        
        # Check if imported class exists
        if [[ "$import_line" == *"com.georgv.audioworkstation"* ]]; then
            import_path=$(echo "$import_line" | sed 's/import //' | sed 's/\./\//g').kt
            import_path="app/src/main/java/$import_path"
            
            if [ ! -f "$import_path" ]; then
                echo -e "   ${RED}❌ Missing: $import_class${NC}"
                ((ERRORS++))
            else
                echo -e "   ${GREEN}✅ Found: $import_class${NC}"
            fi
        fi
    done
    echo ""
}

# Check all Kotlin files for import issues
echo "📂 Checking All Kotlin Files..."
find app/src/main/java -name "*.kt" | head -5 | while read file; do
    validate_imports "$file"
done

echo -e "\n📋 Phase 4: Interface Implementation Validation"
echo "==============================================="

# Function to check interface implementations
validate_interfaces() {
    local file=$1
    echo "🔍 Checking interface implementations in: $(basename $file)"
    
    # Look for class declarations with interfaces
    grep -n "class.*:" "$file" | while read class_line; do
        if [[ "$class_line" == *","* ]]; then
            echo "   Implements interfaces: $class_line"
            
            # Extract interface names
            interfaces=$(echo "$class_line" | sed 's/.*://' | tr ',' '\n')
            echo "$interfaces" | while read interface; do
                interface=$(echo "$interface" | xargs)
                echo "     Interface: $interface"
            done
        fi
    done
    echo ""
}

# Check fragments for interface implementations
echo "📂 Checking Fragments..."
for file in app/src/main/java/com/georgv/audioworkstation/ui/main/fragments/*.kt; do
    if [ -f "$file" ]; then
        validate_interfaces "$file"
    fi
done

echo -e "\n📋 Phase 5: Method Call Validation"
echo "==================================="

# Function to check for method calls to potentially missing methods
validate_method_calls() {
    local file=$1
    echo "🔍 Checking method calls in: $(basename $file)"
    
    # Look for common problematic patterns
    grep -n "viewModel\." "$file" | head -3 | while read method_call; do
        echo "   ViewModel call: $method_call"
    done
    
    # Check for navigation calls
    grep -n "navigate\|findNavController" "$file" | head -2 | while read nav_call; do
        echo "   Navigation call: $nav_call"
    done
    echo ""
}

# Check fragments for method call issues
echo "📂 Checking Method Calls..."
for file in app/src/main/java/com/georgv/audioworkstation/ui/main/fragments/*.kt; do
    if [ -f "$file" ]; then
        validate_method_calls "$file"
    fi
done

echo -e "\n📋 Phase 6: Compilation Error Patterns"
echo "======================================"

echo "🔍 Checking for common error patterns..."

# Check for val reassignment patterns
echo "📝 Checking for val reassignment..."
grep -rn "val.*=" app/src/main/java --include="*.kt" | grep -v "val.*=" | head -3

# Check for unresolved references
echo "📝 Checking for potential unresolved references..."
grep -rn "import.*\$" app/src/main/java --include="*.kt" | head -3

# Check for missing return types
echo "📝 Checking for missing return types in functions..."
grep -rn "fun.*(" app/src/main/java --include="*.kt" | grep -v ": " | grep -v "= " | head -3

# Check for obsolete method calls that may cause unresolved references
echo "📝 Checking for obsolete method calls..."
if grep -rn "loadTracksForCurrentSong\|loadTracksForSong\|refreshTracks\|updateTrackWavPath" app/src/main/java --include="*.kt"; then
    echo "❌ Found calls to potentially removed methods"
    ((ERRORS++))
fi

# Check for common missing imports
echo "📝 Checking for missing imports..."
declare -A missing_imports
missing_imports["Snackbar"]="com.google.android.material.snackbar.Snackbar"
missing_imports["Toast"]="android.widget.Toast"
missing_imports["FrameLayout"]="android.widget.FrameLayout"
missing_imports["LinearLayout"]="android.widget.LinearLayout"

for class_name in "${!missing_imports[@]}"; do
    # Find files that use the class but don't import it
    while IFS= read -r file; do
        if grep -q "\b${class_name}\b" "$file" && ! grep -q "import.*${missing_imports[$class_name]}" "$file"; then
            echo "❌ Missing import in $(basename $file): ${missing_imports[$class_name]}"
            ((ERRORS++))
        fi
    done < <(find app/src/main/java -name "*.kt" -type f)
done

# Check for interface implementation issues
echo "📝 Checking for interface implementation issues..."
while IFS= read -r file; do
    # Look for classes implementing interfaces
    if grep -q "class.*:" "$file"; then
        # Check for override methods that might not match any interface
        if grep -q "override fun" "$file"; then
            # Look for common problematic patterns
            if grep -q "override fun onProcessing" "$file"; then
                echo "❌ Found potentially invalid override methods in $(basename $file)"
                echo "   Check if AudioProcessingCallback interface exists and matches override methods"
                ((ERRORS++))
            fi
        fi
        
        # Check for specific interface implementations that need required methods
        if grep -q "class.*UiListener" "$file"; then
            if ! grep -q "override fun onUiUpdate" "$file"; then
                echo "❌ Class implementing UiListener missing required method in $(basename $file)"
                echo "   UiListener interface requires: override fun onUiUpdate()"
                echo "   This causes 'does not implement abstract member onUiUpdate' error"
                ((ERRORS++))
            fi
        fi
        
        if grep -q "class.*AudioListener" "$file"; then
            if ! grep -q "override fun uiCallback" "$file"; then
                echo "❌ Class implementing AudioListener missing required method in $(basename $file)"
                echo "   AudioListener interface requires: override fun uiCallback()"
                ((ERRORS++))
            fi
        fi
    fi
done < <(find app/src/main/java -name "*.kt" -type f)

# Check for specific syntax structure issues
echo "📝 Checking for syntax structure issues..."
while IFS= read -r file; do
    # Check for very specific problematic patterns
    if grep -q "class.*{" "$file"; then
        # Check for the specific pattern that caused the error: }    }
        if grep -q "}[[:space:]]*}[[:space:]]*$" "$file"; then
            # Make sure it's not just normal nested structures
            line_num=$(grep -n "}[[:space:]]*}[[:space:]]*$" "$file" | head -1 | cut -d: -f1)
            if [ ! -z "$line_num" ]; then
                echo "❌ Potential extra closing brace in $(basename $file) at line $line_num"
                echo "   Found standalone '}' that may be causing 'Expecting top level declaration'"
                ((ERRORS++))
            fi
        fi
    fi
done < <(find app/src/main/java -name "*.kt" -type f)

# Check for interface redeclaration issues
echo "📝 Checking for interface redeclaration issues..."
# Find all interface declarations
declare -A interface_declarations
while IFS= read -r line; do
    file=$(echo "$line" | cut -d: -f1)
    line_num=$(echo "$line" | cut -d: -f2)
    interface_name=$(echo "$line" | grep -o "interface [A-Za-z_][A-Za-z0-9_]*" | cut -d' ' -f2)
    
    if [ ! -z "$interface_name" ]; then
        if [ ! -z "${interface_declarations[$interface_name]}" ]; then
            echo "❌ Interface redeclaration found: $interface_name"
            echo "   First declared in: ${interface_declarations[$interface_name]}"
            echo "   Redeclared in: $(basename $file):$line_num"
            echo "   This causes 'Redeclaration: $interface_name' compilation error"
            ((ERRORS++))
        else
            interface_declarations[$interface_name]="$(basename $file):$line_num"
        fi
    fi
done < <(grep -n "^interface " app/src/main/java -r --include="*.kt")

echo -e "\n📊 Validation Summary"
echo "====================="

if [ $ERRORS -eq 0 ]; then
    echo -e "${GREEN}✅ All validation checks passed!${NC}"
    echo "✨ Code is ready for compilation"
else
    echo -e "${RED}❌ Found $ERRORS validation errors${NC}"
    echo -e "${YELLOW}⚠️  Please fix these issues before committing${NC}"
    exit 1
fi

echo -e "\n🎯 Validation Complete!"