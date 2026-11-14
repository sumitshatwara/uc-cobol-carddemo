import type { SliceModule } from '../types';
import { AccountUpdate } from './AccountUpdate';
import { AccountView } from './AccountView';
import { Dates } from './Dates';
import { Fees } from './Fees';
import { Menu } from './Menu';
import { SignOn } from './SignOn';
import './online.css';

/** Online slice: the CICS screens (COSGN00C, COMEN01C, COACTVWC, COACTUPC) plus the PL/I and HLASM services. */
const online: SliceModule = {
  id: 'online',
  title: 'Online (CICS)',
  nav: [
    { label: 'Online sign-on', path: '/online/signon', testId: 'nav-online-signon' },
    { label: 'Online menu', path: '/online/menu', testId: 'nav-online-menu' },
    { label: 'Account view', path: '/online/accounts', testId: 'nav-online-accounts' },
    { label: 'Account update', path: '/online/accounts/update', testId: 'nav-online-update' },
    { label: 'Late-fee calculator', path: '/online/fees', testId: 'nav-online-fees' },
    { label: 'Date converter', path: '/online/dates', testId: 'nav-online-dates' },
  ],
  routes: [
    { path: '/online/signon', element: <SignOn /> },
    { path: '/online/menu', element: <Menu /> },
    { path: '/online/accounts', element: <AccountView /> },
    { path: '/online/accounts/update', element: <AccountUpdate /> },
    { path: '/online/fees', element: <Fees /> },
    { path: '/online/dates', element: <Dates /> },
  ],
};

export default online;
