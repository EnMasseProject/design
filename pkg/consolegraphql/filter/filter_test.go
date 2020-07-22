/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 *
 */

package filter

import (
	"github.com/stretchr/testify/assert"
	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"testing"
)

func TestParseFilterBooleanExprValid(t *testing.T) {

	testCases := []struct {
		expr string
	}{
		{"1 = 1"},
		{"TRUE = TRUE"},
		{"FALSE = TRUE"},
		{"FALSE = TRUE AND TRUE = FALSE OR TRUE"},
		{"( TRUE = TRUE )"},
		{"( TRUE )"},
	}

	for _, tc := range testCases {
		t.Run(tc.expr, func(t *testing.T) {
			_, err := ParseFilterExpression(tc.expr)
			assert.NoErrorf(t, err, "Unexpected error")
		})
	}
}

func TestParseFilterValueExprValid(t *testing.T) {

	testCases := []struct {
		expr string
	}{
		{"1 > 1"},
		{"1 >= 1"},
		{"2 < 1"},
		{"2 != 1"},
		{"'a' LIKE 'b'"},
	}

	for _, tc := range testCases {
		t.Run(tc.expr, func(t *testing.T) {
			_, err := ParseFilterExpression(tc.expr)
			assert.NoErrorf(t, err, "Unexpected error")
		})
	}
}

func TestFilterEval(t *testing.T) {

	obj := struct {
		FooStr   string
		FooInt   int
		FooInt32 int32
		FooInt64 int64
		FooUint  uint
		FooBool  bool
		FooIntArr   [3]int
		FooStrArr   [3]string
	}{"Bar", 10, 11, 12, 13, true,
		[3]int{1,2,3}, [3]string{"abc", "def", "ghi"}}


	testCases := []struct {
		expr     string
		expected bool
	}{
		{"1 = 1", true},
		{"1 = 2", false},
		{"1 != 2", true},

		{"2 > 1", true},
		{"2 > 2", false},
		{"2 >= 1", true},
		{"2 >= 2", true},

		{"2 < 1", false},
		{"2 < 2", false},
		{"2 <= 1", false},
		{"2 <= 2", true},

		{"1.0 = 1.0", true},
		{"1.0 = 1.0", true},
		{"1.0 = 0.1", false},
		{"1.0 != 0.1", true},
		{"1.1 > 1.0", true},

		{"1 = 1.0", true},
		{"1.0 != 2", true},
		{"2.0 >= 1", true},
		{"-1.9 > -2.0", true},

		{"'A' = 'A'", true},
		{"'A' = 'B'", false},
		{"'A' != 'B'", true},

		{"'B' > 'A'", false}, // String comparison is restricted to = and !=.

		{"'a' LIKE 'a'", true},
		{"'a' LIKE 'b'", false},
		{"'a' LIKE ''", false},
		{"'a' LIKE '%'", true},

		{"'abcd' LIKE 'a%'", true},
		{"'abcd' LIKE 'a%d'", true},
		{"'abcdef' LIKE 'a%c%f'", true},

		{"'abcd' LIKE 'a__d'", true},
		{"'abcd' LIKE 'a_d'", false},

		{"'abcd' LIKE '.*'", false},
		{"'.*' LIKE '.*'", true},

		{"'a' NOT LIKE 'b'", true},

		//{"1 IN [1,2,3]", true},
		//{"4 IN [1,2,3]", false},
		//{"10 IN [1,2,3]", false},
		//{"'abc' IN ['abc','def']", true},
		//{"'ghi' IN ['abc','def']", false},
		//{"'a' IN ['abc','def']", false},

		{"NULL IS NULL", true},
		{"'a' IS NULL", false},
		{"'' IS NULL", false},
		{"0 IS NULL", false},
		{"'a' IS NOT NULL", true},

		{"TRUE = TRUE", true},
		{"TRUE = FALSE", false},
		{"TRUE != FALSE", true},
		{"TRUE", true},
		{"FALSE", false},

		{"NULL = NULL", false},
		{"NULL = 1", false},
		{"NULL = 'NULL'", false},

		{"FALSE AND FALSE", false},
		{"TRUE AND FALSE", false},
		{"FALSE AND TRUE", false},
		{"TRUE AND TRUE", true},

		{"FALSE OR FALSE", false},
		{"TRUE OR FALSE", true},
		{"TRUE OR TRUE", true},

		{"TRUE OR TRUE", true},

		{"NOT (TRUE)", false},
		{"NOT (FALSE)", true},

		{"`$.FooStr` = 'Bar'", true},
		{"`$['FooStr']` = 'Bar'", true},  //Bracket notation is used to evaluate nodes with special characters
		{"`$.FooStr` != 'Bar'", false},
		{"`$.FooStr` = `$.FooStr`", true},

		{"`$.FooInt` = 10", true},
		{"`$.FooInt32` = 11", true},
		{"`$.FooInt64` = 12", true},
		{"`$.FooUint` = 13", true},
		{"`$.FooBool` = true", true},
		{"`$.FooBool` = false", false},

		{"`$.NonExistentNode` != 'Bar'", false},
		{"`$.FooStr.NonExistentSubNode` != 'Bar'", false},

		{"`$.FooInt` IS NOT NULL", true},
		{"`$.NonExistentNode` IS NULL", true},
		{"`$.FooStr.NonExistentSubNode` IS NULL", true},

		{"'1' IN `$.FooIntArr`", true},
		{"'9' IN `$.FooIntArr`", false},
		{"'10' IN `$.FooIntArr`", false},
		{"'abc' IN `$.FooStrArr`", true},
		{"'xyz' IN `$.FooStrArr`", false},
		{"'a' IN `$.FooStrArr`", false},
	}

	for _, tc := range testCases {
		t.Run(tc.expr, func(t *testing.T) {
			expr, err := ParseFilterExpression(tc.expr)
			assert.NoErrorf(t, err, "Unexpected error")
			assert.NotNil(t, expr, "Expected an expression")
			if expr != nil {
				actual, _ := expr.Eval(obj)
				assert.Equal(t, tc.expected, actual, "Unexpected result")
			}
		})

	}
}

func TestFilterKubernetesObjectUsingJsonName(t *testing.T) {

	obj := v1.Namespace{
		ObjectMeta: metav1.ObjectMeta{
			Namespace: "foo",
		},
	}

	testCases := []struct {
		expr     string
		expected bool
	}{
		{"`$.ObjectMeta.Namespace` = 'foo'", true},
		{"`$.metadata.namespace` = 'foo'", true}, // Respects the json struct tag
	}

	for _, tc := range testCases {
		t.Run(tc.expr, func(t *testing.T) {
			expr, err := ParseFilterExpression(tc.expr)
			assert.NoErrorf(t, err, "Unexpected error")
			assert.NotNil(t, expr, "Expected an expression")
			if expr != nil {
				actual, _ := expr.Eval(obj)
				assert.Equal(t, tc.expected, actual, "Unexpected result")
			}
		})
	}
}
