/*
 * Copyright 2020, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 *
 */

package metric

import (
	"fmt"
	"testing"
	"time"

	v1 "github.com/enmasseproject/enmasse/pkg/apis/enmasse/v1"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql"
	"github.com/enmasseproject/enmasse/pkg/consolegraphql/cache"
	"github.com/stretchr/testify/assert"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

func newTestMetricUpdater(t *testing.T) cache.Cache {
	objectCache, err := cache.CreateObjectCache()
	assert.NoError(t, err)

	return objectCache
}

func TestMetricUpdater(t *testing.T) {

	objectCache := newTestMetricUpdater(t)

	namespace := "mynamespace"
	addressspace := "myaddressspace"
	addressname := fmt.Sprintf("%s.myaddr", addressspace)
	addr := createAddress(namespace, addressname, (*consolegraphql.Metric)(consolegraphql.NewRateCalculatingMetric("foo", "mytype", "myunit")))

	now := time.Now()
	addr.Metrics[0].AddTimeSeriesDataPoint(0, now.Add(time.Minute*-1))
	addr.Metrics[0].AddTimeSeriesDataPoint(1500, now)

	assert.Equal(t, float64(0), addr.Metrics[0].Value, "unexpected address metric value")

	err := objectCache.Add(addr)
	assert.NoError(t, err)

	err, updated := UpdateAllMetrics(objectCache, "round(rate(unused_label[5m]), 0.01)")
	assert.NoError(t, err)
	assert.Equal(t, 1, updated)

	objs, err := objectCache.Get(cache.PrimaryObjectIndex, fmt.Sprintf("Address/%s/%s", namespace, addressname), nil)
	assert.NoError(t, err)

	assert.Equal(t, 1, len(objs), "unexpected number of addresses")
	retrievedAddr := objs[0].(*consolegraphql.AddressHolder)
	assert.NotEqual(t, float64(0), retrievedAddr.Metrics[0].Value, "unexpected address metric value")
}

func createAddress(namespace, name string, metrics ...*consolegraphql.Metric) *consolegraphql.AddressHolder {
	return &consolegraphql.AddressHolder{
		MessagingAddress: v1.MessagingAddress{
			TypeMeta: metav1.TypeMeta{
				Kind: "MessagingAddress",
			},
			ObjectMeta: metav1.ObjectMeta{
				Name:      name,
				Namespace: namespace,
			},
		},
		Metrics: metrics,
	}
}
