% Test rules for Prolog integration
% These rules test authorization logic

% Basic approval rules
can_approve(User) :-
    user_role(User, 'admin').

can_approve(User) :-
    user_role(User, 'manager'),
    user_years_of_tenure(User, Years),
    Years >= 2.

% Expense approval rules
can_approve_expense(User, Expense) :-
    user_role(User, 'manager'),
    expense_amount(Expense, Amount),
    Amount =< 5000.

can_approve_expense(User, _) :-
    user_role(User, 'director').

can_approve_expense(User, Expense) :-
    user_role(User, 'admin'),
    expense_category(Expense, 'operational').

% Second approval requirements
requires_second_approval(Expense) :-
    expense_amount(Expense, Amount),
    Amount > 10000.

requires_second_approval(Expense) :-
    expense_category(Expense, 'capital'),
    expense_amount(Expense, Amount),
    Amount > 5000.

% Access control
can_access_sensitive_data(User) :-
    user_department(User, 'security'),
    user_role(User, 'analyst').

can_access_sensitive_data(User) :-
    user_role(User, 'admin').

% Composite conditions
is_senior_manager(User) :-
    user_role(User, 'manager'),
    user_years_of_tenure(User, Years),
    Years >= 5.
