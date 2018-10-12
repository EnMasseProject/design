var rhea = require('rhea');

function coalesce (f, delay, max_delay) {
    var start, scheduled, timeout = undefined;
    var timeout = undefined;

    function fire() {
        start = undefined;
        timeout = undefined;
        f();
    }

    function can_delay() {
        return start && scheduled < (start + max_delay);
    }

    function schedule() {
        timeout = setTimeout(fire, delay);
        scheduled = Date.now() + delay;
    }

    return function () {
        if (timeout) {
            if (can_delay()) {
                clearTimeout(timeout);
                schedule();
            } // else just wait for previously scheduled call
        } else {
            start = Date.now();
            schedule();
        }
    }
};

function TimeSeries(label, max_size) {
    this.yData = [label];
    this.xData = ['time'];
    this.max_size = max_size | 10;
    this.last = undefined;
}

TimeSeries.prototype.push = function (value) {
    if (this.xData.length > this.max_size) {
        this.xData.splice(1, 1);
        this.yData.splice(1, 1);
    }
    this.yData.push(value);
    this.xData.push(new Date().getTime());
}

TimeSeries.prototype.push_delta = function (value) {
    if (this.last === undefined) {
        this.push(value);
    } else {
        this.push(value - this.last);
    }
    this.last = value;
}

function WindowedDelta(name, window) {
    this.name = name;
    this.last = undefined;
    this.deltas = Array(window).fill(0);
    this.current = 0;
}

WindowedDelta.prototype.push = function (value) {
    this.deltas[this.current++] = value;
    if (this.current >= this.deltas.length) {
        this.current = this.current % this.deltas.length;
    }
};

WindowedDelta.prototype.update = function (value) {
    if (this.last !== undefined && this.last <= value) {
        this.push(value - this.last);
    }
    this.last = value;
};

WindowedDelta.prototype.total = function (current) {
    return this.deltas.reduce(function(a, b) { return a + b; });
};

function AddressDefinition(a) {
    this.update(a);
    this.depth_series = new TimeSeries('messages-stored');
    this.depth_series_config = {
        chartId      : 'depth-' + this.address,
        tooltipType  : 'actual',
        title        : 'Messages Stored',
        layout       : 'compact',
        trendLabel   : 'Messages Stored',
        valueType    : 'actual',
        timeFrame    : 'Last 5 Minutes',
        units        : ''
    };
    this.update_depth_series();
    this.periodic_deltas = {
        'messages_in': new WindowedDelta(this.address + ' messages_in', 60),
        'messages_out': new WindowedDelta(this.address + ' messages_out', 60)
    };
    for (var name in this.periodic_deltas) {
        this.define_periodic_delta(name);
        this.periodic_deltas[name].update(this[name]);
    }
}

AddressDefinition.prototype.define_periodic_delta = function (name) {
    Object.defineProperty(this, name + '_delta', { get: function () { return this.periodic_deltas[name].total(this[name]); } });
}

AddressDefinition.prototype.update = function (a) {
    for (var k in a) {
        this[k] = a[k];
    }
    if (a.type === 'subscription' && a.shards) {
        this.messages_in = a.shards[0].enqueued;
        this.messages_out = a.shards[0].acknowledged + a.shards[0].killed;
    }
}

AddressDefinition.prototype.update_depth_series = function () {
    if ((this.type === 'queue' || this.type === 'topic') && this.depth !== undefined) {
        this.depth_series.push(this.depth);
        return true;
    } else {
        return false;
    }
}

AddressDefinition.prototype.update_periodic_deltas = function () {
    for (var name in this.periodic_deltas) {
        this.periodic_deltas[name].update(this[name]);
    }
    return true;
}

Object.values = function values(O) {
		return reduce(keys(O), (v, k) => concat(v, typeof k === 'string' && isEnumerable(O, k) ? [O[k]] : []), []);
};

function get_items_from_index(index) {
    var items = [];
    for (var k in index) {
        items.push(index[k]);
    }
    return items;
}

function AddressService($http) {
    var self = this;  // 'this' is not available in the success funtion of $http.get
    this.admin_disabled = true;
    this.address_index = {};
    this.connection_index = {};
    Object.defineProperty(this, 'addresses', { get: function () { return get_items_from_index(self.address_index); } });
    Object.defineProperty(this, 'connections', { get: function () { return get_items_from_index(self.connection_index); } });
    this.address_types = [];
    this.address_space_type = '';
    this.users = [];
    var ws = rhea.websocket_connect(WebSocket);
    this.connection = rhea.connect({"connection_details":ws("wss://" + location.hostname + ":" + location.port + "/websocket", ["binary", "AMQPWSB10"]), "reconnect":true, rejectUnauthorized:true});
    this.connection.on('message', this.on_message.bind(this));
    this.sender = this.connection.open_sender();
    this.connection.open_receiver();
    setInterval(this.update_periodic_deltas.bind(this), 5000);
    setInterval(this.update_depth_series.bind(this), 30000);

    this.tooltip = {}
    $http.get('tooltips.json')
      .then(function (d) {
        self.tooltip = d.data;
      })
}

function by_name(name) {
    return function (o) {
        return o.name === name;
    }
}

AddressService.prototype.get_plan_display_name = function (type, plan) {
    var t = this.address_types.filter(by_name(type))
    if (t.length) {
        var p = t[0].plans.filter(by_name(plan));
        if (p.length) {
            return p[0].displayName;
        } else {
            console.log('found no plan called %s address of type %s', plan, type);
        }
    } else if (this.address_types.length) {
        console.log('found no address for type %s in %j', type, this.address_types);
    }
    return plan;
};

AddressService.prototype.get_valid_plans = function (type) {
    var l = this.address_types.filter(function (f) { return f.name === type; })
    return l.length ? l[0].plans : [];
};

AddressService.prototype.get_valid_address_types = function () {
    return this.address_types;
};

AddressService.prototype.list_topic_names = function () {
    var topic_names = [];
    for (var key in this.address_index) {
        var a = this.address_index[key];
        if (a.type === 'topic') {
            topic_names.push(a.address);
        }
    }
    return topic_names;
};

AddressService.prototype.update_depth_series = function () {
    var changed = false;
    for (var key in this.address_index) {
        if (this.address_index[key].update_depth_series()) {
            changed = true;
        }
    }
    if (changed && this.callback) this.callback('update_depth_series');
};

AddressService.prototype.update_periodic_deltas = function () {
    for (var key in this.address_index) {
        this.address_index[key].update_periodic_deltas();
    }
    if (this.callback) this.callback('reset_periodic_deltas');
};

AddressService.prototype.create_address = function (obj) {
    this.sender.send({subject: 'create_address', body: obj});
}

AddressService.prototype.delete_selected = function () {
    var changed = false;
    for (var key in this.address_index) {
        var a = this.address_index[key];
        if (a.selected) {
            this.sender.send({subject: 'delete_address', body: a});
            delete this.address_index[key];
            changed = true;
        }
    }
    if (changed && this.callback) this.callback('address_deleted');
}

AddressService.prototype.is_unique_valid_name = function (name) {
    return this.address_index[name] === undefined && name.match(/^[^#*\/\s\.:]+$/);
}

AddressService.prototype.create_user = function (obj) {
    console.log('creating user: ' + JSON.stringify(obj));
    this.sender.send({subject: 'create_user', body: obj});
}

AddressService.prototype.delete_selected_users = function () {
    for (var i = 0; i < this.users.length;) {
        if (this.users[i].selected) {
            this.sender.send({subject: 'delete_user', body: this.users[i].name});
            this.users.splice(i, 1);
        } else {
            i++;
        }
    }
}

AddressService.prototype.update_user = function (c) {
    var i = 0;
    while (i < this.users.length && c.name !== this.users[i].name) {
        i++;
    }
    this.users[i] = c;
}

AddressService.prototype.on_message = function (context) {
    if (context.message.subject === 'address') {
        var a = context.message.body;
        var def = this.address_index[a.address];
        if (def === undefined) {
            this.address_index[a.address] = new AddressDefinition(a);
            this.callback('address_added');
        } else {
            def.update(a);
            this.callback('address_updated');
        }
    } else if (context.message.subject === 'address_deleted') {
        if (this.address_index[context.message.body]) {
            delete this.address_index[context.message.body];
            if (this.callback) this.callback('address_deleted');
        }
    } else if (context.message.subject === 'address_types') {
        this.address_types = context.message.body;
        this.address_space_type = context.message.application_properties.address_space_type;
        this.admin_disabled = context.message.application_properties.disable_admin;
        if (this.callback) this.callback('address_types');
    } else if (context.message.subject === 'connection') {
        var c = context.message.body;
        var def = this.connection_index[c.id];
        if (def === undefined) {
            this.connection_index[c.id] = c;
            this.callback('connection_added');
        } else {
            // don't replace existing connection items, just update them
            Object.assign(def, c);
            this.callback('connection_updated');
        }
    } else if (context.message.subject === 'connection_deleted') {
        if (this.connection_index[context.message.body]) {
            delete this.connection_index[context.message.body];
            if (this.callback) this.callback('connection_deleted');
        }
    } else if (context.message.subject === 'user') {
        console.log('got user: ' + JSON.stringify(context.message.body));
        this.update_user(context.message.body);
        if (this.callback) this.callback("user");
    } else if (context.message.subject === 'user_deleted') {
        var changed = false;
        for (var i = 0; i < this.users.length;) {
            if (this.users[i].id === context.message.body) {
                this.users.splice(i, 1);
                changed = true;
            } else {
                i++;
            }
        }
        if (changed && this.callback) this.callback("user:deleted");
    }
}

AddressService.prototype._notify = function () {
    for (var reason in this._reasons) {
        this._callback(reason);
    }
    this._reasons = {};
}

AddressService.prototype.on_update = function (callback) {
    this._reasons = {};
    this._callback = callback;
    this.notify = coalesce(this._notify.bind(this), 10, 500);
    var self = this;
    this.callback = function (reason) {
        self._reasons[reason] = true;
        this.notify();
    }
}

angular.module('address_service', []).factory('address_service', function($http) {
    return new AddressService($http);
});
